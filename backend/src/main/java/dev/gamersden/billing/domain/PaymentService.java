package dev.gamersden.billing.domain;

import dev.gamersden.billing.repo.PaymentSplitRepository;
import dev.gamersden.billing.repo.TransactionRepository;
import dev.gamersden.common.config.VenueTime;
import dev.gamersden.common.error.ConflictException;
import dev.gamersden.common.error.ErrorCode;
import dev.gamersden.common.error.NotFoundException;
import dev.gamersden.common.error.ValidationFailedException;
import dev.gamersden.common.security.CurrentStaff;
import dev.gamersden.common.security.StaffPrincipal;
import dev.gamersden.common.spi.CartSettlement;
import dev.gamersden.common.spi.MemberPointsLookup;
import dev.gamersden.common.spi.MemberSettlement;
import dev.gamersden.common.spi.MemberSettlement.LoyaltyMovement;
import dev.gamersden.common.spi.SaleReceiptPrinting;
import dev.gamersden.common.spi.SessionSettlement;
import dev.gamersden.common.spi.ShiftLookup;
import dev.gamersden.common.spi.StationLookup;
import dev.gamersden.common.spi.TournamentEntrySale;
import dev.gamersden.common.spi.TournamentEntrySettlement;
import dev.gamersden.common.spi.TournamentEntrySettlement.EntrySale;
import dev.gamersden.common.spi.TournamentEntrySettlement.QuotedEntry;
import dev.gamersden.common.spi.TournamentEntrySettlement.RegisteredEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * {@code POST /payments} and {@code POST /payments/{id}/void} — the spine everything else hangs
 * off (ARCHITECTURE.md §1).
 *
 * <h2>One transaction, or none of it</h2>
 *
 * <p>Invariant §5.3: a settle writes the transaction snapshot, its tenders, the blocks it paid
 * for, the stock it sold, the ledgers it moved <em>and the print job</em> in a single database
 * transaction. Every door this class writes through is declared {@code Propagation.MANDATORY}, so
 * that is not a convention anyone can forget — there is no code path that can decrement stock,
 * stamp a block or queue a receipt outside the transaction that took the money. A 409 anywhere
 * leaves zero rows behind, which is why the whole of {@link Settlement} runs before the first
 * insert.
 *
 * <p>Idempotency (§5.2) is deliberately not implemented here. {@code POST /payments} is on the
 * guarded route list, and {@code IdempotencyFilter} claims the key <em>before</em> the request
 * reaches this class and replays the stored response afterwards. A retried settle never re-enters
 * this code at all, and gets back the same {@code transactionId} and {@code printJobId} it was
 * given the first time.
 *
 * <h2>What a settle is not</h2>
 *
 * <p>Paying is not ending. Blocks stop being billable and the seat keeps its clock, its state and
 * its time — {@code GET /sessions/{id}/bill} then charges only for what has been bought since
 * (invariant §5.9). And nothing derived is stored: the transaction keeps the four gross buckets,
 * the points discount and the total that was tendered, never a rebuilt bill (§5.4).
 *
 * <h2>Voiding</h2>
 *
 * <p>A void is a second transaction, not an edit. The sale row stays exactly as it was printed —
 * flagged, with its reason — and an equal-and-opposite reversal carries the money back out
 * (invariant §5.7). Same shift, Manager+, and every side effect the sale had is undone through the
 * same doors that applied it.
 */
@Service
public class PaymentService implements TournamentEntrySale {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private static final String COUNTER_SALE = "Counter sale";

    /** The receipt heading of a sale that is nothing but tournament entries. */
    private static final String ENTRY_SALE = "Tournament entries";

    private final TransactionRepository transactions;
    private final PaymentSplitRepository splits;
    private final BillService bills;
    private final SessionSettlement sessions;
    private final CartSettlement carts;
    private final MemberSettlement memberWrites;
    private final MemberPointsLookup memberReads;
    private final SaleReceiptPrinting receipts;
    private final StationLookup stations;
    private final ShiftLookup shifts;
    private final TournamentEntrySettlement tournamentEntries;
    private final PublicIdSequence publicIds;
    private final Clock clock;

    public PaymentService(TransactionRepository transactions,
                          PaymentSplitRepository splits,
                          BillService bills,
                          SessionSettlement sessions,
                          CartSettlement carts,
                          MemberSettlement memberWrites,
                          MemberPointsLookup memberReads,
                          SaleReceiptPrinting receipts,
                          StationLookup stations,
                          ShiftLookup shifts,
                          TournamentEntrySettlement tournamentEntries,
                          PublicIdSequence publicIds,
                          Clock clock) {
        this.transactions = transactions;
        this.splits = splits;
        this.bills = bills;
        this.sessions = sessions;
        this.carts = carts;
        this.memberWrites = memberWrites;
        this.memberReads = memberReads;
        this.receipts = receipts;
        this.stations = stations;
        this.shifts = shifts;
        this.tournamentEntries = tournamentEntries;
        this.publicIds = publicIds;
        this.clock = clock;
    }

    // ---- POST /payments --------------------------------------------------------------------

    /**
     * Settles a seat, a counter cart, or a handful of tournament entries on their own.
     *
     * @param sessionId    {@code target.sessionId} — the whole bill: unbilled blocks, the open cart
     * @param cartId       {@code target.cartId} — a counter sale
     * @param redeemPoints points to spend against the bill, capped at what is owed
     * @param entrySales   {@code tournamentEntries[]} — registered in this same transaction, at
     *                     the fee quoted under the tournament's row lock (docs/tournaments.md §5)
     * @param tenders      the split panel's rows; they must sum to what is left after the discount
     */
    @Transactional
    public SettleResult settle(Long sessionId, Long cartId, Integer redeemPoints,
                               List<EntrySale> entrySales, List<Tender> tenders) {
        Settled settled = write(sessionId, cartId, redeemPoints, entrySales, tenders);
        return new SettleResult(settled.tx().getId(), settled.tx().getPublicId(),
                settled.printJobId(),
                settled.entries().isEmpty() ? null : settled.entries().stream()
                        .map(RegisteredEntry::qrToken).toList(),
                null);
    }

    /**
     * {@code POST /tournaments/{id}/entries} — the {@code tournament} package's counter route into
     * exactly the same settle (api-contract.md, Tournaments). No session, no cart and no member:
     * one entry, its fee, and the receipt carrying its P5 stub, written in the one transaction
     * {@link #write} always writes.
     */
    @Override
    @Transactional
    public Sold sell(long tournamentId, String playerName, List<TenderLine> tenders) {
        List<Tender> tendered = tenders == null ? List.of() : tenders.stream()
                .map(line -> new Tender(PaymentMethod.parse(line.method()), line.amount(),
                        line.paymentRef()))
                .toList();
        Settled settled = write(null, null, null,
                List.of(new EntrySale(tournamentId, playerName)), tendered);
        RegisteredEntry entry = settled.entries().get(0);
        return new Sold(settled.tx().getId(), settled.tx().getPublicId(), settled.printJobId(),
                entry.entryId(), entry.seed(), entry.qrToken());
    }

    /**
     * The one transaction (invariant §5.3): the snapshot and its tenders, the blocks it pays for,
     * the cart it closes, the entries it registers, the loyalty it moves and the receipt it
     * queues. Nothing is written before {@link Settlement} has accepted the request, so a 409
     * leaves the database exactly as it found it.
     */
    private Settled write(Long sessionId, Long cartId, Integer redeemPoints,
                          List<EntrySale> entrySales, List<Tender> tenders) {
        StaffPrincipal staff = CurrentStaff.require();
        long shiftId = requireOpenShift(staff);
        OffsetDateTime at = VenueTime.now(clock);

        Target target = resolve(sessionId, cartId, entrySales);
        Settlement settlement = Settlement.of(target.charges(), target.member(), redeemPoints, tenders);

        Transaction tx = snapshot(target, settlement, shiftId, staff);
        settlement.tenders().forEach(tender -> splits.save(
                new PaymentSplit(tx.getId(), tender.method(), tender.amount(), tender.paymentRef())));

        if (target.sessionId() != null && target.charges().gaming() > 0) {
            SessionSettlement.PaidBlocks paid =
                    sessions.markUnpaidBlocksPaid(target.sessionId(), tx.getId());
            requireUnmoved("gaming", target.charges().gaming(), paid.amount());
        }
        if (target.chargesCart()) {
            requireUnmoved("F&B", target.charges().fnb(),
                    carts.settle(target.cart().cartId(), tx.getId()));
        }
        List<RegisteredEntry> registered =
                tournamentEntries.register(tx.getId(), settlement.memberId(), target.entries());
        if (settlement.memberId() != null) {
            memberWrites.applySale(settlement.memberId(), tx.getId(), settlement.loyalty());
        }

        long printJobId = receipts.issueSaleReceipt(
                receiptOf(tx, target, settlement, staff, at, registered));
        log.info("transaction {} ({}) settled {} BDT on shift {} by staff {} — gaming {}, fnb {}, "
                        + "tournament {} ({} entries), points -{}/+{}; print job {}",
                tx.getId(), tx.getPublicId(), tx.getTotalDue(), shiftId, staff.id(),
                tx.getGamingAmount(), tx.getFnbAmount(), tx.getTournamentAmount(),
                registered.size(), tx.getPointsRedeemed(), tx.getPointsEarned(), printJobId);
        return new Settled(tx, printJobId, registered);
    }

    /** What one settle wrote, before it is narrowed to whichever response shape asked for it. */
    private record Settled(Transaction tx, long printJobId, List<RegisteredEntry> entries) {
    }

    /**
     * The immutable money row (§5.4), flushed immediately because everything written after it —
     * splits, blocks, stock movements, ledger rows, the print job — references its id.
     */
    private Transaction snapshot(Target target, Settlement settlement, long shiftId, StaffPrincipal staff) {
        Transaction tx = new Transaction(publicIds.next(VenueTime.now(clock)), shiftId, staff.id(),
                settlement.totalDue());
        tx.setSessionId(target.sessionId());
        tx.setCartId(target.chargesCart() ? target.cart().cartId() : null);
        tx.setMemberId(settlement.memberId());
        tx.setGamingAmount(target.charges().gaming());
        tx.setFnbAmount(target.charges().fnb());
        tx.setTournamentAmount(target.charges().tournament());
        tx.setBookingAmount(target.charges().booking());
        tx.setPointsRedeemed(settlement.pointsRedeemed());
        tx.setPointsEarned(settlement.pointsEarned());
        return transactions.saveAndFlush(tx);
    }

    // ---- POST /payments/{id}/void -----------------------------------------------------------

    /**
     * Reverses a sale in full: a negative transaction with negated tenders, the blocks it paid for
     * released back to billable, the stock it sold put back with {@code VOID} movements, and the
     * loyalty it moved handed back — all in one transaction, exactly like the settle it undoes.
     *
     * <p>Same shift only. A void changes what is in the drawer, so it has to land in the shift
     * that will be counted against it; correcting an earlier shift's sale is a refund decision,
     * not a void.
     */
    @Transactional
    public VoidResult voidPayment(long transactionId, String reason) {
        StaffPrincipal staff = CurrentStaff.require();
        long shiftId = requireOpenShift(staff);
        if (reason == null || reason.isBlank()) {
            throw ValidationFailedException.onField("reason", "A void has to say why");
        }
        Transaction original = transactions.findById(transactionId)
                .orElseThrow(() -> new NotFoundException("Transaction", transactionId));
        requireVoidable(original, shiftId);

        List<PaymentSplit> tendered = splits.findByTxId(original.getId());
        Transaction reversal = reversalOf(original, shiftId, staff);
        tendered.forEach(split -> splits.save(new PaymentSplit(reversal.getId(), split.getMethod(),
                -split.getAmount(), split.getPaymentRef())));

        original.setVoided(true);
        original.setVoidReason(reason.trim());

        int released = sessions.releaseBlocksPaidBy(original.getId());
        if (original.getCartId() != null) {
            carts.reverse(original.getCartId(), reversal.getId());
        }
        if (original.getMemberId() != null) {
            memberWrites.reverseSale(original.getMemberId(), reversal.getId(),
                    new LoyaltyMovement(original.getPointsRedeemed(), original.getPointsEarned(),
                            walletSpentOn(tendered)));
        }

        log.info("transaction {} ({}) voided by staff {} on shift {}: \"{}\" — reversal {} ({}) "
                        + "for {} BDT, {} blocks released back to billable",
                original.getId(), original.getPublicId(), staff.id(), shiftId,
                original.getVoidReason(), reversal.getId(), reversal.getPublicId(),
                reversal.getTotalDue(), released);
        return new VoidResult(reversal.getId(), reversal.getPublicId(), original.getId(),
                original.getPublicId(), reversal.getTotalDue());
    }

    /**
     * The mirror image of the sale, posted to the shift doing the refunding (invariant §5.7). Every
     * figure is negated, including the loyalty columns, so summing a shift's transactions gives the
     * same answer whether or not anything was voided in it.
     */
    private Transaction reversalOf(Transaction original, long shiftId, StaffPrincipal staff) {
        Transaction reversal = new Transaction(publicIds.next(VenueTime.now(clock)), shiftId,
                staff.id(), -original.getTotalDue());
        reversal.setSessionId(original.getSessionId());
        reversal.setCartId(original.getCartId());
        reversal.setMemberId(original.getMemberId());
        reversal.setGamingAmount(-original.getGamingAmount());
        reversal.setFnbAmount(-original.getFnbAmount());
        reversal.setTournamentAmount(-original.getTournamentAmount());
        reversal.setBookingAmount(-original.getBookingAmount());
        reversal.setPointsRedeemed(-original.getPointsRedeemed());
        reversal.setPointsEarned(-original.getPointsEarned());
        return transactions.saveAndFlush(reversal);
    }

    private void requireVoidable(Transaction original, long shiftId) {
        if (original.isVoided()) {
            throw new ConflictException(ErrorCode.CONFLICT,
                    "Transaction %s is already voided".formatted(original.getPublicId()),
                    Map.of("transactionId", original.getId()));
        }
        if (original.getTotalDue() < 0) {
            throw new ConflictException(ErrorCode.CONFLICT,
                    "Transaction %s is itself a refund — there is nothing to reverse"
                            .formatted(original.getPublicId()),
                    Map.of("transactionId", original.getId()));
        }
        if (original.getShiftId() != shiftId) {
            throw new ConflictException(ErrorCode.CONFLICT,
                    "Transaction %s belongs to shift %d — a void has to land in the shift that will "
                            + "be counted".formatted(original.getPublicId(), original.getShiftId()),
                    Map.of("transactionId", original.getId(), "shiftId", original.getShiftId()));
        }
    }

    private static int walletSpentOn(List<PaymentSplit> tendered) {
        return tendered.stream()
                .filter(split -> split.getMethod() == PaymentMethod.WALLET)
                .mapToInt(PaymentSplit::getAmount)
                .sum();
    }

    // ---- targets -----------------------------------------------------------------------------

    /**
     * {@code target} carries exactly one of {@code sessionId} / {@code cartId} (api-contract.md).
     * A seat settles its whole bill; a counter cart settles itself.
     *
     * <p>Tournament entries are the one thing that can be sold with no target at all: a walk-up
     * buying nothing but a ticket has no seat and no basket, and refusing them a sale because the
     * request has no {@code cartId} would only teach the floor to open an empty cart first. Every
     * other combination is still 400 — including a bare {@code target: {}} with nothing to sell.
     *
     * <p>The entries are quoted here, before anything is written, so 409 {@code TOURNAMENT_FULL}
     * and {@code TOURNAMENT_NOT_OPEN} land with the rest of the refusals and leave nothing behind.
     */
    private Target resolve(Long sessionId, Long cartId, List<EntrySale> entrySales) {
        if (sessionId != null && cartId != null) {
            throw ValidationFailedException.onField("target",
                    "A payment settles exactly one of sessionId or cartId");
        }
        List<EntrySale> sales = entrySales == null ? List.of() : entrySales;
        if (sessionId == null && cartId == null && sales.isEmpty()) {
            throw ValidationFailedException.onField("target",
                    "A payment settles exactly one of sessionId or cartId");
        }
        Target target = sessionId != null ? seat(sessionId)
                : cartId != null ? counter(cartId)
                : entriesOnly();
        Target withEntries = target.with(tournamentEntries.quote(sales, target.member().name()));
        if (withEntries.charges().gross() == 0) {
            throw ValidationFailedException.onField("target",
                    "There is nothing to settle — nothing on this bill is owed");
        }
        return withEntries;
    }

    /** A walk-up buying only a ticket: no seat, no basket, no member, no loyalty. */
    private static Target entriesOnly() {
        return new Target(null, null, new Charges(0, 0, 0, 0), Bill.Member.NONE, ENTRY_SALE,
                List.of(), List.of());
    }

    /**
     * The seat's bill, computed inside this transaction so the figures charged for and the rows
     * marked paid are the same rows. {@link #requireUnmoved} is the belt to that braces: another
     * terminal adding a block between the two reads would otherwise have its half hour marked paid
     * by a receipt that never charged for it.
     */
    private Target seat(long sessionId) {
        Bill bill = bills.of(sessionId);
        CartSettlement.SettleableCart cart = carts.findForSession(sessionId)
                .filter(open -> !open.settled())
                .orElse(null);
        List<SaleReceiptPrinting.Line> lines = bill.lines().stream()
                .map(line -> new SaleReceiptPrinting.Line(line.label(), line.qty(), line.amount()))
                .toList();
        return new Target(sessionId, cart,
                new Charges(bill.gamingDue(), bill.fnbDue(), bill.tournamentDue(), 0),
                bill.member(), stationName(bill.stationId()), lines, List.of());
    }

    /**
     * A counter sale: F&amp;B only, and no member — the payment body carries no {@code memberId}
     * and a counter cart has no seat to inherit one from, so loyalty simply does not apply.
     */
    private Target counter(long cartId) {
        CartSettlement.SettleableCart cart = carts.find(cartId)
                .orElseThrow(() -> new NotFoundException("Cart", cartId));
        if (cart.settled()) {
            throw new ConflictException(ErrorCode.CONFLICT,
                    "Cart %d has already been paid for".formatted(cartId), Map.of("cartId", cartId));
        }
        if (cart.sessionId() != null) {
            throw ValidationFailedException.onField("target",
                    "Cart %d belongs to session %d — settle the session so its time is charged too"
                            .formatted(cartId, cart.sessionId()));
        }
        List<SaleReceiptPrinting.Line> lines = cart.lines().stream()
                .map(line -> new SaleReceiptPrinting.Line(line.name(), line.qty(), line.lineTotal()))
                .toList();
        return new Target(null, cart, Charges.fnb(cart.total()), Bill.Member.NONE, COUNTER_SALE,
                lines, List.of());
    }

    /**
     * A charge that moved between the quote and the write means the bill on screen is not the bill
     * being paid. 409, and the transaction rolls back — re-reading the bill costs the operator a
     * second; charging the wrong amount costs the venue an argument.
     */
    private static void requireUnmoved(String what, int charged, int written) {
        if (charged != written) {
            throw new ConflictException(ErrorCode.CONFLICT,
                    "The %s on this bill changed while it was being settled — re-read the bill"
                            .formatted(what),
                    Map.of("charged", charged, "actual", written));
        }
    }

    // ---- helpers -----------------------------------------------------------------------------

    /** Every transaction belongs to a shift — that is how it reconciles later (invariant §5.7). */
    private long requireOpenShift(StaffPrincipal staff) {
        return shifts.openShiftId(staff.terminal())
                .orElseThrow(() -> new ConflictException(ErrorCode.CONFLICT,
                        "No shift is open on %s — open one before taking money"
                                .formatted(staff.terminal()),
                        Map.of("terminal", staff.terminal())));
    }

    private String stationName(long stationId) {
        return stations.find(stationId)
                .map(StationLookup.StationInfo::name)
                .orElseGet(() -> "Station " + stationId);
    }

    /**
     * The receipt's content — what P1 prints, not how. The loyalty balance is read back after the
     * ledgers have moved, so the "points earned · balance" line shows the customer the number they
     * will walk out with rather than the one they walked in with.
     */
    private SaleReceiptPrinting.SaleReceipt receiptOf(Transaction tx, Target target,
                                                      Settlement settlement, StaffPrincipal staff,
                                                      OffsetDateTime at,
                                                      List<RegisteredEntry> registered) {
        List<SaleReceiptPrinting.Tender> tenders = settlement.tenders().stream()
                .map(tender -> new SaleReceiptPrinting.Tender(tender.method().name(),
                        tender.amount(), tender.paymentRef()))
                .toList();
        Integer balance = settlement.memberId() == null
                ? null
                : memberReads.loyaltyOf(settlement.memberId())
                        .map(MemberPointsLookup.Loyalty::points)
                        .orElse(null);
        List<SaleReceiptPrinting.EntryStub> stubs = registered.stream()
                .map(entry -> new SaleReceiptPrinting.EntryStub(entry.entryId(),
                        entry.tournamentName(), entry.playerName(), entry.seed(), entry.qrToken()))
                .toList();
        return new SaleReceiptPrinting.SaleReceipt(tx.getId(), tx.getPublicId(), target.heading(),
                staff.terminal(), staff.id(), at, target.receiptLines(), tx.getTotalDue(), tenders,
                tx.getPointsRedeemed(), tx.getPointsEarned(), balance, stubs);
    }

    /**
     * What is being settled, resolved once. {@code cart} is the seat's or the counter's open cart;
     * it is only <em>charged</em> when the bill actually has an F&amp;B figure, so a settle for
     * time alone never closes a cart the customer is still adding to.
     *
     * @param entries the tournament entries this sale will register, already priced and seeded
     */
    private record Target(Long sessionId,
                          CartSettlement.SettleableCart cart,
                          Charges charges,
                          Bill.Member member,
                          String heading,
                          List<SaleReceiptPrinting.Line> receiptLines,
                          List<QuotedEntry> entries) {

        /**
         * The same target with the quoted entries folded in — their fees into the tournament
         * bucket, their names onto the receipt as {@code «event» · TOKEN #NN}.
         */
        Target with(List<QuotedEntry> quoted) {
            if (quoted.isEmpty()) {
                return this;
            }
            List<SaleReceiptPrinting.Line> lines = new java.util.ArrayList<>(receiptLines);
            quoted.forEach(entry -> lines.add(new SaleReceiptPrinting.Line(
                    "%s · TOKEN #%02d".formatted(entry.tournamentName(), entry.seed()), 1,
                    entry.fee())));
            int fees = quoted.stream().mapToInt(QuotedEntry::fee).sum();
            return new Target(sessionId, cart,
                    new Charges(charges.gaming(), charges.fnb(), charges.tournament() + fees,
                            charges.booking()),
                    member, heading, List.copyOf(lines), quoted);
        }

        boolean chargesCart() {
            return cart != null && charges.fnb() > 0;
        }
    }
}
