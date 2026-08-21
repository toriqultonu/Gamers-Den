package dev.gamersden.billing.domain;

import dev.gamersden.common.spi.CartLookup;
import dev.gamersden.common.spi.MemberPointsLookup;
import dev.gamersden.common.spi.SessionBillLookup;
import dev.gamersden.common.spi.TournamentBillLookup;
import dev.gamersden.common.util.Money;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /sessions/{id}/bill} — what this seat owes right now. Pure arithmetic over what the
 * three lookup doors handed back: nothing here reads a repository, and nothing here is ever stored
 * (invariant §5.4 — derived values are computed at read time, and only the transaction snapshot
 * that a settle writes is permanent).
 *
 * <p>Three rules decide every figure:
 *
 * <ol>
 *   <li><strong>Gaming is unbilled blocks only.</strong> A block carrying a {@code paid_tx_id} has
 *       already been paid for — prepaid at a booking or play-ticket sale, or settled mid-session
 *       while the clock kept running — and is never charged again (invariant §5.9). It moves to
 *       {@link #prepaidCredit()} instead, so the panel can show the customer what their time is
 *       worth and what is already covered without ever double-charging for it.</li>
 *   <li><strong>Prices are snapshots.</strong> Blocks are grouped by the rate they were sold at,
 *       so a session that crossed the morning-discount boundary bills two honest lines rather than
 *       one blended average.</li>
 *   <li><strong>Redemption is a cap, not a discount.</strong> {@code pointsRedeemable} is
 *       {@code min(points, netTotal)} (api-contract.md §1); the bill quotes the ceiling and
 *       {@code POST /payments} is what actually spends the points.</li>
 * </ol>
 *
 * <p>The arithmetic contract the FE's bill panel can rely on:
 * {@code sum(lines) == gamingDue + fnbDue + tournamentDue == netTotal}. {@code prepaidCredit} is
 * deliberately <em>outside</em> that sum — it is a credit note, already excluded, never something
 * to subtract a second time.
 *
 * @param serverTime the instant the bill was computed; the panel counts from it, never from the
 *                   browser's clock (invariant §5.1)
 */
public record Bill(long sessionId,
                   long stationId,
                   String sessionState,
                   Member member,
                   List<BillLine> lines,
                   int gamingDue,
                   int fnbDue,
                   int tournamentDue,
                   int netTotal,
                   int billableBlocks,
                   int prepaidBlocks,
                   int prepaidCredit,
                   int pointsRedeemable,
                   OffsetDateTime serverTime) {

    /**
     * Assembles the bill from the four reads behind it.
     *
     * @param cart   the session's unsettled cart, or {@code null} when there is nothing open
     * @param member the attached member's loyalty balances, or {@code null} on a walk-in seat
     */
    public static Bill of(SessionBillLookup.BillableSession session,
                          CartLookup.UnsettledCart cart,
                          List<TournamentBillLookup.TournamentCharge> entries,
                          MemberPointsLookup.Loyalty member) {
        List<BillLine> lines = new ArrayList<>(gamingLines(session.blocks()));
        lines.addAll(fnbLines(cart));
        lines.addAll(tournamentLines(entries));

        int gaming = totalOf(lines, BillLineKind.GAMING);
        int fnb = totalOf(lines, BillLineKind.FNB);
        int tournament = totalOf(lines, BillLineKind.TOURNAMENT);
        int netTotal = gaming + fnb + tournament;

        List<SessionBillLookup.TimeBlock> paid = session.blocks().stream()
                .filter(SessionBillLookup.TimeBlock::paid)
                .toList();
        int points = member == null ? 0 : member.points();

        return new Bill(session.sessionId(),
                session.stationId(),
                session.state(),
                Member.of(member),
                List.copyOf(lines),
                gaming, fnb, tournament, netTotal,
                session.blocks().size() - paid.size(),
                paid.size(),
                paid.stream().mapToInt(SessionBillLookup.TimeBlock::price).sum(),
                Money.cappedRedemption(points, netTotal),
                session.serverTime());
    }

    /**
     * One line per distinct rate, oldest rate first — normally exactly one, two when the session
     * spans the morning-discount boundary. Blocks already paid for are filtered out here: this is
     * the "unbilled blocks only" rule, and the only place it lives.
     */
    private static List<BillLine> gamingLines(List<SessionBillLookup.TimeBlock> blocks) {
        Map<Integer, Integer> countByPrice = new LinkedHashMap<>();
        blocks.stream()
                .filter(block -> !block.paid())
                .forEach(block -> countByPrice.merge(block.price(), 1, Integer::sum));
        return countByPrice.entrySet().stream()
                .map(rate -> BillLine.gaming(rate.getValue(), rate.getKey()))
                .toList();
    }

    private static List<BillLine> fnbLines(CartLookup.UnsettledCart cart) {
        if (cart == null) {
            return List.of();
        }
        return cart.lines().stream()
                .map(line -> new BillLine(BillLineKind.FNB, line.itemId(), line.name(),
                        line.qty(), line.unitPrice()))
                .toList();
    }

    private static List<BillLine> tournamentLines(List<TournamentBillLookup.TournamentCharge> entries) {
        return entries.stream()
                .map(entry -> new BillLine(BillLineKind.TOURNAMENT, entry.entryId(),
                        label(entry), 1, entry.fee()))
                .toList();
    }

    private static String label(TournamentBillLookup.TournamentCharge entry) {
        return entry.playerName() == null || entry.playerName().isBlank()
                ? entry.tournamentName()
                : entry.tournamentName() + " · " + entry.playerName();
    }

    private static int totalOf(List<BillLine> lines, BillLineKind kind) {
        return lines.stream().filter(line -> line.kind() == kind).mapToInt(BillLine::amount).sum();
    }

    /** What the time on this seat is worth in total — billable plus already covered. */
    public int gamingValue() {
        return gamingDue + prepaidCredit;
    }

    /** True when there is nothing left to charge for: the seat can end (invariant §5.9). */
    public boolean isSettled() {
        return netTotal == 0;
    }

    /**
     * The member on the bill, flattened for the wire, or {@link #NONE} on a walk-in seat.
     * {@code wallet} rides along because the split-payment panel needs the floor it may not spend
     * past; the bill itself never touches either balance.
     */
    public record Member(Long id, String name, int points, int wallet) {

        public static final Member NONE = new Member(null, null, 0, 0);

        static Member of(MemberPointsLookup.Loyalty loyalty) {
            return loyalty == null
                    ? NONE
                    : new Member(loyalty.memberId(), loyalty.name(), loyalty.points(), loyalty.wallet());
        }

        public boolean attached() {
            return id != null;
        }
    }
}
