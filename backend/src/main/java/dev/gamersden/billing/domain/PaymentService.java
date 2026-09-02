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
import dev.gamersden.common.spi.BookingSale;
import dev.gamersden.common.spi.BookingSettlement;
import dev.gamersden.common.spi.CartSettlement;
import dev.gamersden.common.spi.MemberPointsLookup;
import dev.gamersden.common.spi.MemberSettlement;
import dev.gamersden.common.spi.MemberSettlement.LoyaltyMovement;
import dev.gamersden.common.spi.PlayTicketSale;
import dev.gamersden.common.spi.PlayTicketSettlement;
import dev.gamersden.common.spi.PlayTicketSettlement.IssuedTicket;
import dev.gamersden.common.spi.PlayTicketSettlement.QuotedTicket;
import dev.gamersden.common.spi.PlayTicketSettlement.TicketSale;
import dev.gamersden.common.spi.SaleReceiptPrinting;
import dev.gamersden.common.spi.SessionSettlement;
import dev.gamersden.common.spi.ShiftLookup;
import dev.gamersden.common.spi.StationLookup;
import dev.gamersden.common.spi.SyncOutboxWriter;
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
public class PaymentService implements TournamentEntrySale, BookingSale, PlayTicketSale {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private static final String COUNTER_SALE = "Counter sale";

    /** The receipt heading of a sale that is nothing but tournament entries. */
    private static final String ENTRY_SALE = "Tournament entries";

    /** The receipt heading of a sale that is nothing but walk-up play tickets. */
    private static final String TICKET_SALE = "Play tickets";

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
    private final BookingSettlement bookings;
    private final PlayTicketSettlement playTickets;
    private final PublicIdSequence publicIds;
    private final SyncOutboxWriter outbox;
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
                          BookingSettlement bookings,
                          PlayTicketSettlement playTickets,
                          PublicIdSequence publicIds,
                          SyncOutboxWriter outbox,
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
        this.bookings = bookings;
        this.playTickets = playTickets;
        this.publicIds = publicIds;
        this.outbox = outbox;
        this.clock = clock;
    }

    // ---- POST /payments --------------------------------------------------------------------

    /**
     * Settles a seat, a counter cart, or a handful of tournament entries and play tickets on their
     * own.
     *
     * @param sessionId    {@code target.sessionId} — the whole bill: unbilled blocks, the open cart
     * @param cartId       {@code target.cartId} — a counter sale
     * @param redeemPoints points to spend against the bill, capped at what is owed
     * @param entrySales   {@code tournamentEntries[]} — registered in this same transaction, at
     *                     the fee quoted under the tournament's row lock (docs/tournaments.md §5)
     * @param ticketSales  {@code playTickets[]} — each takes the next daily queue token and enters
     *                     the play queue as WAITING, in this same transaction (docs/bookings.md §3)
     * @param tenders      the split panel's rows; they must sum to what is left after the discount
     */
    @Transactional
    public SettleResult settle(Long sessionId, Long cartId, Integer redeemPoints,
                               List<EntrySale> entrySales, List<TicketSale> ticketSales,
                               List<Tender> tenders) {
        Settled settled = write(sessionId, cartId, redeemPoints, entrySales, ticketSales, tenders);
        return new SettleResult(settled.tx().getId(), settled.tx().getPublicId(),
                settled.printJobId(),
                settled.entries().isEmpty() ? null : settled.entries().stream()
                        .map(RegisteredEntry::qrToken).toList(),
                settled.tickets().isEmpty() ? null : settled.tickets().stream()
                        .map(ticket -> new SettleResult.QueueToken(ticket.queueEntryId(),
                                ticket.tokenNo(), ticket.tokenDate()))
                        .toList());
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
                List.of(new EntrySale(tournamentId, playerName)), List.of(), tendered);
        RegisteredEntry entry = settled.entries().get(0);
        return new Sold(settled.tx().getId(), settled.tx().getPublicId(), settled.printJobId(),
                entry.entryId(), entry.seed(), entry.qrToken());
    }

    /**
     * {@code POST /bookings} — the {@code booking} package's route into exactly the same settle
     * (api-contract.md, Pre-bookings; docs/bookings.md §2). A booking is pay-first, so it begins
     * life here: the prepaid play time and the package fee go into the {@code booking_amount}
     * bucket, the booking row is written through {@link BookingSettlement} inside this transaction,
     * and the receipt carries its P7 confirmation on the same job (invariants §5.3, §5.7).
     *
     * <p>The amounts arrive already priced. {@code booking} snapshots the console's block rate and
     * the package fee before calling, so there is exactly one figure in play and the money row and
     * the booking row cannot disagree about what was charged.
     */
    @Override
    @Transactional
    public SoldBooking sell(Order order) {
        Settled settled = write(booking(order), null, List.of(new Tender(
                PaymentMethod.parse(order.method()), order.total(), order.paymentRef())));
        return new SoldBooking(settled.tx().getId(), settled.tx().getPublicId(),
                settled.printJobId(), settled.booking().bookingId(),
                settled.booking().overlappingBookingIds());
    }

    /**
     * {@code POST /play-tickets} — the {@code queue} package's counter route into exactly the same
     * settle (api-contract.md, "Play queue"). One ticket, no seat, no basket and no member: the
     * price comes off the console's rate card here rather than from the caller, and the tender is
     * then exactly what was priced, so there is nothing for two figures to disagree about.
     *
     * <p>The target is resolved <em>before</em> the tender is built for that reason — a play
     * ticket carries no snapshot of its own the way a booking does, so the quote is the only price
     * there is.
     */
    @Override
    @Transactional
    public SoldTicket sell(TicketOrder order) {
        Target target = resolve(null, null, List.of(),
                List.of(new TicketSale(order.consoleType(), order.blocks(), order.playerName())));
        int due = target.charges().gross();
        Settled settled = write(target, null, List.of(new Tender(
                PaymentMethod.parse(order.method()), due, order.paymentRef())));
        return new SoldTicket(settled.tx().getId(), settled.tx().getPublicId(),
                settled.printJobId(), settled.tickets().get(0), due);
    }

    /**
     * The one transaction (invariant §5.3): the snapshot and its tenders, the blocks it pays for,
     * the cart it closes, the entries it registers, the loyalty it moves and the receipt it
     * queues. Nothing is written before {@link Settlement} has accepted the request, so a 409
     * leaves the database exactly as it found it.
     */
    private Settled write(Long sessionId, Long cartId, Integer redeemPoints,
                          List<EntrySale> entrySales, List<TicketSale> ticketSales,
                          List<Tender> tenders) {
        return write(resolve(sessionId, cartId, entrySales, ticketSales), redeemPoints, tenders);
    }

    /** The same transaction, once the target has been resolved and priced. */
    private Settled write(Target target, Integer redeemPoints, List<Tender> tenders) {
        StaffPrincipal staff = CurrentStaff.require();
        long shiftId = requireOpenShift(staff);
        OffsetDateTime at = VenueTime.now(clock);

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
        List<IssuedTicket> issued = playTickets.register(tx.getId(), target.tickets());
        BookingSettlement.Registered heldSlot = target.booking() == null
                ? null
                : bookings.register(tx.getId(), target.booking());
        if (settlement.memberId() != null) {
            memberWrites.applySale(settlement.memberId(), tx.getId(), settlement.loyalty());
        }

        long printJobId = receipts.issueSaleReceipt(
                receiptOf(tx, target, settlement, staff, at, registered, heldSlot, issued));
        // The op ships with the money, in the money's own transaction (invariant §5.8). A settle
        // that is refused therefore tells the cloud nothing, and one that commits cannot fail to.
        outbox.record(SyncOutboxWriter.TRANSACTIONS, SyncOutboxWriter.SETTLED, tx.getId(),
                SyncOutboxWriter.data(
                        "publicId", tx.getPublicId(),
                        "shiftId", shiftId,
                        "staffId", staff.id(),
                        "sessionId", tx.getSessionId(),
                        "cartId", tx.getCartId(),
                        "memberId", tx.getMemberId(),
                        "gamingAmount", tx.getGamingAmount(),
                        "fnbAmount", tx.getFnbAmount(),
                        "tournamentAmount", tx.getTournamentAmount(),
                        "bookingAmount", tx.getBookingAmount(),
                        "pointsRedeemed", tx.getPointsRedeemed(),
                        "pointsEarned", tx.getPointsEarned(),
                        "totalDue", tx.getTotalDue(),
                        "tenders", settlement.tenders().stream()
                                .map(tender -> Map.of("method", tender.method().name(),
                                        "amount", tender.amount()))
                                .toList(),
                        "entryIds", registered.stream().map(RegisteredEntry::entryId).toList(),
                        "queueEntryIds", issued.stream().map(IssuedTicket::queueEntryId).toList(),
                        "bookingId", heldSlot == null ? null : heldSlot.bookingId(),
                        "printJobId", printJobId));
        log.info("transaction {} ({}) settled {} BDT on shift {} by staff {} — gaming {}, fnb {}, "
                        + "tournament {} ({} entries), booking {} ({} play tickets), "
                        + "points -{}/+{}; print job {}",
                tx.getId(), tx.getPublicId(), tx.getTotalDue(), shiftId, staff.id(),
                tx.getGamingAmount(), tx.getFnbAmount(), tx.getTournamentAmount(),
                registered.size(), tx.getBookingAmount(), issued.size(), tx.getPointsRedeemed(),
                tx.getPointsEarned(), printJobId);
        return new Settled(tx, printJobId, registered, heldSlot, issued);
    }

    /** What one settle wrote, before it is narrowed to whichever response shape asked for it. */
    private record Settled(Transaction tx, long printJobId, List<RegisteredEntry> entries,
                           BookingSettlement.Registered booking, List<IssuedTicket> tickets) {
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
        int revoked = playTickets.revoke(original.getId());
        if (original.getCartId() != null) {
            carts.reverse(original.getCartId(), reversal.getId());
        }
        if (original.getMemberId() != null) {
            memberWrites.reverseSale(original.getMemberId(), reversal.getId(),
                    new LoyaltyMovement(original.getPointsRedeemed(), original.getPointsEarned(),
                            walletSpentOn(tendered)));
        }

        outbox.record(SyncOutboxWriter.TRANSACTIONS, SyncOutboxWriter.VOIDED, reversal.getId(),
                SyncOutboxWriter.data(
                        "publicId", reversal.getPublicId(),
                        "shiftId", shiftId,
                        "staffId", staff.id(),
                        "reversalOf", original.getId(),
                        "reversalOfPublicId", original.getPublicId(),
                        "reason", original.getVoidReason(),
                        "totalDue", reversal.getTotalDue(),
                        "blocksReleased", released,
                        "tokensRevoked", revoked));
        log.info("transaction {} ({}) voided by staff {} on shift {}: \"{}\" — reversal {} ({}) "
                        + "for {} BDT, {} blocks released back to billable, {} queue token(s) revoked",
                original.getId(), original.getPublicId(), staff.id(), shiftId,
                original.getVoidReason(), reversal.getId(), reversal.getPublicId(),
                reversal.getTotalDue(), released, revoked);
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
     * <p>Tournament entries and play tickets are the two things that can be sold with no target
     * at all: a walk-up buying nothing but a ticket has no seat and no basket, and refusing them a
     * sale because the request has no {@code cartId} would only teach the floor to open an empty
     * cart first. A play ticket goes further — it is on sale <em>because</em> every console is
     * busy, so "no seat" is its normal case, not an edge one (docs/bookings.md §3). Every other
     * combination is still 400, including a bare {@code target: {}} with nothing to sell.
     *
     * <p>Both are quoted here, before anything is written, so 409 {@code TOURNAMENT_FULL},
     * {@code TOURNAMENT_NOT_OPEN} and an unknown console type land with the rest of the refusals
     * and leave nothing behind.
     */
    private Target resolve(Long sessionId, Long cartId, List<EntrySale> entrySales,
                           List<TicketSale> ticketSales) {
        if (sessionId != null && cartId != null) {
            throw ValidationFailedException.onField("target",
                    "A payment settles exactly one of sessionId or cartId");
        }
        List<EntrySale> sales = entrySales == null ? List.of() : entrySales;
        List<TicketSale> tickets = ticketSales == null ? List.of() : ticketSales;
        if (sessionId == null && cartId == null && sales.isEmpty() && tickets.isEmpty()) {
            throw ValidationFailedException.onField("target",
                    "A payment settles exactly one of sessionId or cartId");
        }
        Target target = sessionId != null ? seat(sessionId)
                : cartId != null ? counter(cartId)
                : walkUp(!sales.isEmpty(), !tickets.isEmpty());
        Target priced = target
                .with(tournamentEntries.quote(sales, target.member().name()))
                .withTickets(playTickets.quote(tickets, target.member().name()));
        if (priced.charges().gross() == 0) {
            throw ValidationFailedException.onField("target",
                    "There is nothing to settle — nothing on this bill is owed");
        }
        return priced;
    }

    /**
     * A booking: no seat and no basket — the time has not been played yet — but a member if one
     * was attached, so the wallet can pay for it and the sale earns its points like any other.
     *
     * <p>The whole charge lands in the {@code booking} bucket, prepaid play time and package fee
     * alike: that is the column the X/Z "Pre-booking" line adds up (docs/bookings.md §6), and
     * splitting the play half into {@code gaming_amount} would double-count it against the floor
     * when the token is finally seated.
     */
    private Target booking(Order order) {
        Bill.Member member = order.memberId() == null
                ? Bill.Member.NONE
                : Bill.Member.of(memberReads.loyaltyOf(order.memberId())
                        .orElseThrow(() -> new NotFoundException("Member", order.memberId())));
        List<SaleReceiptPrinting.Line> lines = new java.util.ArrayList<>(2);
        lines.add(new SaleReceiptPrinting.Line("BOOKING %dx30M".formatted(order.blocks()),
                order.blocks(), order.playAmount()));
        if (order.packageFee() > 0) {
            lines.add(new SaleReceiptPrinting.Line("PACKAGE FEE", 1, order.packageFee()));
        }
        return new Target(null, null, new Charges(0, 0, 0, order.total()), member,
                order.stationName(), List.copyOf(lines), List.of(), List.of(), order);
    }

    /**
     * A walk-up buying only tickets: no seat, no basket, no member, no loyalty. The heading names
     * whichever kind of ticket it is, and falls back to the counter when it is both.
     */
    private static Target walkUp(boolean hasEntries, boolean hasTickets) {
        String heading = hasEntries && hasTickets ? COUNTER_SALE
                : hasTickets ? TICKET_SALE
                : ENTRY_SALE;
        return new Target(null, null, new Charges(0, 0, 0, 0), Bill.Member.NONE, heading,
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
                                                      List<RegisteredEntry> registered,
                                                      BookingSettlement.Registered heldSlot,
                                                      List<IssuedTicket> issued) {
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
        List<SaleReceiptPrinting.PlayTicketStub> ticketStubs = issued.stream()
                .map(ticket -> new SaleReceiptPrinting.PlayTicketStub(ticket.queueEntryId(),
                        ticket.tokenNo(), ticket.tokenDate(), ticket.playerName(),
                        ticket.consoleType(), ticket.blocks()))
                .toList();
        return new SaleReceiptPrinting.SaleReceipt(tx.getId(), tx.getPublicId(), target.heading(),
                staff.terminal(), staff.id(), at, target.receiptLines(), tx.getTotalDue(), tenders,
                tx.getPointsRedeemed(), tx.getPointsEarned(), balance, stubs,
                bookingStubOf(target.booking(), heldSlot), ticketStubs);
    }

    /**
     * P7, on the same job as the P1 it was paid on (invariant §5.5). The cancellation deadline is
     * computed from the order's own cutoff snapshot, so the paper promises the customer exactly
     * what {@code POST /bookings/{id}/cancel} will later enforce (invariant §5.11).
     */
    private static SaleReceiptPrinting.BookingStub bookingStubOf(Order order,
                                                                 BookingSettlement.Registered held) {
        if (order == null || held == null) {
            return null;
        }
        return new SaleReceiptPrinting.BookingStub(held.bookingId(), order.stationName(),
                order.consoleType(), order.name(), order.phone(), order.startAt(), order.blocks(),
                order.playAmount(), order.packageFee(),
                order.startAt().minusHours(order.cutoffHours()));
    }

    /**
     * What is being settled, resolved once. {@code cart} is the seat's or the counter's open cart;
     * it is only <em>charged</em> when the bill actually has an F&amp;B figure, so a settle for
     * time alone never closes a cart the customer is still adding to.
     *
     * @param entries the tournament entries this sale will register, already priced and seeded
     * @param tickets the play tickets this sale will issue tokens for, already priced
     * @param booking the booking this sale will hold, or {@code null} on every other sale
     */
    private record Target(Long sessionId,
                          CartSettlement.SettleableCart cart,
                          Charges charges,
                          Bill.Member member,
                          String heading,
                          List<SaleReceiptPrinting.Line> receiptLines,
                          List<QuotedEntry> entries,
                          List<QuotedTicket> tickets,
                          Order booking) {

        Target(Long sessionId, CartSettlement.SettleableCart cart, Charges charges,
               Bill.Member member, String heading, List<SaleReceiptPrinting.Line> receiptLines,
               List<QuotedEntry> entries) {
            this(sessionId, cart, charges, member, heading, receiptLines, entries, List.of(), null);
        }

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
                    member, heading, List.copyOf(lines), quoted, tickets, booking);
        }

        /**
         * The same target with the quoted play tickets folded in. Their money lands in the
         * {@code booking} bucket beside pre-bookings, because that is the column the X/Z
         * "Pre-booking" line adds up (docs/bookings.md §6): both are time paid for in advance and
         * not yet played, and putting a ticket in {@code gaming_amount} would count the same half
         * hour twice — once at the counter and again when the token is seated and the prepaid
         * blocks land on a session.
         */
        Target withTickets(List<QuotedTicket> quoted) {
            if (quoted.isEmpty()) {
                return this;
            }
            List<SaleReceiptPrinting.Line> lines = new java.util.ArrayList<>(receiptLines);
            quoted.forEach(ticket -> lines.add(new SaleReceiptPrinting.Line(
                    "PLAY %s %dx30M".formatted(ticket.consoleType(), ticket.blocks()),
                    ticket.blocks(), ticket.amount())));
            int paid = quoted.stream().mapToInt(QuotedTicket::amount).sum();
            return new Target(sessionId, cart,
                    new Charges(charges.gaming(), charges.fnb(), charges.tournament(),
                            charges.booking() + paid),
                    member, heading, List.copyOf(lines), entries, quoted, booking);
        }

        boolean chargesCart() {
            return cart != null && charges.fnb() > 0;
        }
    }
}
