package dev.gamersden.billing.web;

import dev.gamersden.billing.domain.Bill;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The bill panel's contract (api-contract.md, {@code GET /sessions/{id}/bill}). Every figure here
 * is derived at read time and nothing is stored (invariant §5.4) — ask twice a minute apart on a
 * running seat and the gaming line will have grown.
 *
 * <p>Two guarantees the panel is built on:
 *
 * <ul>
 *   <li>{@code sum(lines[].amount) == gamingDue + fnbDue + tournamentDue == netTotal}.</li>
 *   <li>{@code prepaidCredit} is <strong>not</strong> part of that sum. Prepaid and
 *       already-settled blocks were charged once already (invariant §5.9); the field is there so
 *       the panel can show "2 h prepaid · ৳320 covered" as a credit note, and subtracting it again
 *       would refund the customer money nobody took.</li>
 * </ul>
 *
 * @param gamingValue      what the seat's time is worth in total — {@code gamingDue + prepaidCredit}
 * @param billableBlocks   30-minute blocks still to pay for
 * @param prepaidBlocks    blocks already carrying a {@code paid_tx_id}
 * @param pointsRedeemable {@code min(member points, netTotal)} — the ceiling {@code POST /payments}
 *                         will enforce, quoted here so the panel's slider cannot ask for more
 * @param settled          true when nothing is due, which is also what lets the session end
 * @param serverTime       when this bill was computed; countdowns measure from it (invariant §5.1)
 */
@Schema(name = "Bill", description = "What a session owes right now — unbilled blocks, F&B, "
        + "tournament entries, and the prepaid credit already covering it")
public record BillView(long sessionId,
                       long stationId,
                       String sessionState,
                       Long memberId,
                       String memberName,
                       int memberPoints,
                       int memberWallet,
                       List<BillLineView> lines,
                       int gamingDue,
                       int fnbDue,
                       int tournamentDue,
                       int netTotal,
                       int gamingValue,
                       int billableBlocks,
                       int prepaidBlocks,
                       int prepaidCredit,
                       int pointsRedeemable,
                       boolean settled,
                       OffsetDateTime serverTime) {

    public static BillView of(Bill bill) {
        Bill.Member member = bill.member();
        return new BillView(
                bill.sessionId(),
                bill.stationId(),
                bill.sessionState(),
                member.id(),
                member.name(),
                member.points(),
                member.wallet(),
                bill.lines().stream().map(BillLineView::of).toList(),
                bill.gamingDue(),
                bill.fnbDue(),
                bill.tournamentDue(),
                bill.netTotal(),
                bill.gamingValue(),
                bill.billableBlocks(),
                bill.prepaidBlocks(),
                bill.prepaidCredit(),
                bill.pointsRedeemable(),
                bill.isSettled(),
                bill.serverTime());
    }
}
