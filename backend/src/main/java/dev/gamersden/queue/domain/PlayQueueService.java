package dev.gamersden.queue.domain;

import dev.gamersden.common.error.ConflictException;
import dev.gamersden.common.error.ErrorCode;
import dev.gamersden.common.error.NotFoundException;
import dev.gamersden.common.spi.PlayTicketSale;
import dev.gamersden.common.spi.SaleRefunding;
import dev.gamersden.common.spi.SessionSeating;
import dev.gamersden.common.spi.StationLookup;
import dev.gamersden.queue.repo.QueueEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The play queue of docs/bookings.md §3 — the rail the Floor reads, the standalone ticket sale,
 * seating a token, and taking a no-show back out.
 *
 * <pre>
 * sold / checked in --&gt; WAITING --seat--&gt; SEATED
 *                          \--refund &amp; remove (Manager+)--&gt; REFUNDED
 * </pre>
 *
 * <p>Four rules shape it.
 *
 * <p><strong>A ticket sells while every console is busy.</strong> That is what the queue is for
 * (§3), so nothing here asks whether a seat is free — only seating does, and by then the customer
 * already has a token and a place in line.
 *
 * <p><strong>Staff seat anybody, not the head of the queue.</strong> The rail is ordered so the
 * operator can see who has waited longest, but the customer chooses; {@link #seat} takes whichever
 * token was tapped (§3). Console type is the one thing not negotiable — 409
 * {@code CONSOLE_TYPE_MISMATCH}, enforced in {@code session} where the seat is actually created.
 *
 * <p><strong>The entry id is the key, not the number.</strong> A token that goes unseated over
 * venue midnight keeps working: the counter restarts for new sales, and yesterday's row is still
 * WAITING carrying its own {@code token_date} (docs/bookings.md §7, invariant §5.10).
 *
 * <p><strong>Removing a no-show is money.</strong> It writes a negative transaction through
 * {@link SaleRefunding} in the same transaction that takes the token out of the queue (invariant
 * §5.7), which is why it is Manager+ and why the row is left as REFUNDED rather than deleted — the
 * token was issued, and the record of it is what the refund hangs off.
 */
@Service
public class PlayQueueService {

    private static final Logger log = LoggerFactory.getLogger(PlayQueueService.class);

    private final QueueEntryRepository entries;
    private final QueueTokenService tokens;
    private final PlayTicketSale sales;
    private final SaleRefunding refunds;
    private final SessionSeating seats;
    private final StationLookup stations;

    public PlayQueueService(QueueEntryRepository entries,
                            QueueTokenService tokens,
                            PlayTicketSale sales,
                            SaleRefunding refunds,
                            SessionSeating seats,
                            StationLookup stations) {
        this.entries = entries;
        this.tokens = tokens;
        this.sales = sales;
        this.refunds = refunds;
        this.seats = seats;
        this.stations = stations;
    }

    // ---- GET /play-queue ----------------------------------------------------------------------

    /**
     * The rail: everyone waiting first, in counter order, then today's seated tokens as history
     * (api-contract.md, "Play queue").
     *
     * <p>The waiting half is deliberately not filtered to today. A customer who paid yesterday and
     * never got a console is still owed their time, so their token stays at the head of the list
     * carrying its issue date; only the seated strip is a day's worth, because that is history and
     * history is read a day at a time.
     */
    @Transactional(readOnly = true)
    public List<QueueEntry> rail() {
        List<QueueEntry> rail = new ArrayList<>(tokens.waiting());
        rail.addAll(tokens.seatedToday());
        return List.copyOf(rail);
    }

    // ---- POST /play-tickets -------------------------------------------------------------------

    /**
     * The standalone alias for one walk-up ticket (api-contract.md, "Play queue"). Not a second
     * money path: it goes straight back through {@code billing}'s settle, which prices the ticket,
     * writes the transaction and its tender, issues the token through {@link QueueTokenService}
     * and queues the P1 receipt carrying its P6 stub — all in the one transaction
     * {@code POST /payments} always writes (invariant §5.3).
     */
    @Transactional
    public PlayTicketSale.SoldTicket sell(String consoleType, int blocks, String playerName,
                                          String method, String paymentRef) {
        PlayTicketSale.SoldTicket sold = sales.sell(new PlayTicketSale.TicketOrder(
                consoleType, blocks, playerName, method, paymentRef));
        log.info("play ticket sold as TOKEN #{} of {} (queue entry {}) for {} BDT on transaction "
                        + "{} ({}), print job {}", sold.ticket().tokenNo(),
                sold.ticket().tokenDate(), sold.ticket().queueEntryId(), sold.amount(),
                sold.transactionId(), sold.publicId(), sold.printJobId());
        return sold;
    }

    // ---- POST /play-queue/{id}/seat -----------------------------------------------------------

    /**
     * Seats one waiting token on a free console (api-contract.md, "Play queue").
     *
     * <p>The whole seat — the session row, its prepaid blocks born paid with the sale's
     * {@code paid_tx_id}, the token to SEATED, and for a booking the slot to USED — is the single
     * transaction {@code session} runs (invariant §5.9). This method only names the token and
     * hands it over, so the rail and {@code POST /sessions} can never drift apart.
     *
     * <p>The WAITING check here is a courtesy: it turns a stale rail into a clear 409 without
     * touching the station. The binding one is taken under the row lock inside the seat itself.
     */
    @Transactional
    public Seated seat(long queueEntryId, long stationId) {
        QueueEntry entry = entries.findById(queueEntryId)
                .orElseThrow(() -> new NotFoundException("Queue entry", queueEntryId));
        requireWaiting(entry, "seat");

        SessionSeating.SeatedSession session = seats.seat(stationId, queueEntryId);

        log.info("TOKEN #{} of {} (queue entry {}) seated on station {} as session {} with {} "
                        + "prepaid blocks", entry.getTokenNo(), entry.getTokenDate(), queueEntryId,
                stationId, session.sessionId(), session.blocks());
        return new Seated(entry, session, stations.find(stationId)
                .map(StationLookup.StationInfo::name)
                .orElseGet(() -> "Station " + stationId));
    }

    // ---- DELETE /play-queue/{id} --------------------------------------------------------------

    /**
     * Refunds and removes a no-show (docs/bookings.md §3). Manager+, and one transaction: the
     * negative transaction against the sale and the token flipped to REFUNDED land together, so
     * money can never go back out on a token that is still seatable, nor a token be killed without
     * the money following it.
     *
     * <p>The amount is the snapshot the token was sold at, not what the rate card says now
     * (invariant §5.11) — the customer gets back exactly what they handed over.
     *
     * <p>Walk-up tickets only. A checked-in booking's token belongs to a sale that also took a
     * package fee, and docs/bookings.md §7 is explicit about that case: the way to hand that money
     * back is a Manager+ void of the transaction, which reverses the whole sale and revokes this
     * token with it. Refunding half of it here would leave the booking standing as ARRIVED against
     * a sale that had been partly undone.
     */
    @Transactional
    public Removed remove(long queueEntryId, String reason) {
        QueueEntry entry = entries.findByIdForUpdate(queueEntryId)
                .orElseThrow(() -> new NotFoundException("Queue entry", queueEntryId));
        requireWaiting(entry, "refund");
        requireWalkUp(entry);

        String why = reason == null || reason.isBlank()
                ? "TOKEN #%02d of %s did not show".formatted(entry.getTokenNo(), entry.getTokenDate())
                : reason.trim();
        SaleRefunding.Refund refund = entry.getPlayAmount() > 0
                ? refunds.refund(new SaleRefunding.RefundRequest(entry.getTxId(),
                        entry.getPlayAmount(), SaleRefunding.Bucket.BOOKING, why))
                : null;
        entry.setStatus(QueueEntryStatus.REFUNDED);

        log.info("queue entry {} (TOKEN #{} of {}) removed as a no-show ({}) — {} BDT returned on {}",
                entry.getId(), entry.getTokenNo(), entry.getTokenDate(), why, entry.getPlayAmount(),
                refund == null ? "nothing (it was sold for 0)" : refund.publicId());
        return new Removed(entry, refund);
    }

    // ---- guards -------------------------------------------------------------------------------

    /** A token is spent once — seating or refunding one twice is the same mistake, twice over. */
    private static void requireWaiting(QueueEntry entry, String what) {
        if (entry.isWaiting()) {
            return;
        }
        String message = entry.getStatus() == QueueEntryStatus.SEATED
                ? "TOKEN #%02d has already been seated — there is nothing to %s"
                        .formatted(entry.getTokenNo(), what)
                : "TOKEN #%02d was already refunded and removed from the queue"
                        .formatted(entry.getTokenNo());
        throw new ConflictException(ErrorCode.CONFLICT, message,
                Map.of("queueEntryId", entry.getId(), "status", entry.getStatus().name()));
    }

    private static void requireWalkUp(QueueEntry entry) {
        if (entry.getSource() != QueueEntrySource.BOOKING) {
            return;
        }
        throw new ConflictException(ErrorCode.CONFLICT,
                ("TOKEN #%02d belongs to a booking — void transaction %d to hand that money back, "
                        + "which revokes this token with it")
                        .formatted(entry.getTokenNo(), entry.getTxId()),
                Map.of("queueEntryId", entry.getId(), "bookingId", entry.getBookingId(),
                        "transactionId", entry.getTxId()));
    }

    // ---- results ------------------------------------------------------------------------------

    /** @param stationName what the Floor card the token just landed on is called */
    public record Seated(QueueEntry entry, SessionSeating.SeatedSession session, String stationName) {
    }

    /** @param refund {@code null} only when the token was somehow sold for nothing at all */
    public record Removed(QueueEntry entry, SaleRefunding.Refund refund) {
    }
}
