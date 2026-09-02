package dev.gamersden.queue.domain;

import dev.gamersden.common.error.ConflictException;
import dev.gamersden.common.error.ErrorCode;
import dev.gamersden.common.error.NotFoundException;
import dev.gamersden.common.events.LiveEvents;
import dev.gamersden.common.spi.BookingSeatLookup;
import dev.gamersden.common.spi.PrepaidSeatLookup;
import dev.gamersden.common.spi.SyncOutboxWriter;
import dev.gamersden.queue.repo.QueueEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

/**
 * The {@code queue} package's answer to {@link PrepaidSeatLookup} — the other half of the seat
 * transaction {@code SessionService} runs (invariant §5.9).
 *
 * <p>One token, two ways in. {@code POST /sessions} with a {@code queueEntryId} names the token
 * directly, which is what the Floor's queue rail sends; with a {@code bookingId} it names the slot,
 * and the booking's own token is looked up through {@link BookingSeatLookup}. Both land on the
 * same {@code queue_entries} row, so a booking and a walk-up are seated by identical code — the
 * only difference is that a booking has a second row to flip to USED afterwards.
 *
 * <p>{@link #consume} is {@link Propagation#MANDATORY}: the token is marked used inside the
 * transaction that created the session and inserted its prepaid blocks, so there is no window in
 * which a token is spent but nobody is sitting down, or a session exists that two terminals could
 * both load the same prepaid time onto.
 *
 * <p>Deliberately a bean of its own rather than part of {@code PlayQueueService}: the seat call
 * runs {@code queue → session → queue}, and splitting the inbound door from the outbound one is
 * what keeps that from being a construction cycle.
 */
@Service
public class PrepaidSeatLookupService implements PrepaidSeatLookup {

    private final QueueEntryRepository entries;
    private final BookingSeatLookup bookings;
    private final LiveEvents live;
    private final SyncOutboxWriter outbox;

    public PrepaidSeatLookupService(QueueEntryRepository entries, BookingSeatLookup bookings,
                                    LiveEvents live, SyncOutboxWriter outbox) {
        this.entries = entries;
        this.bookings = bookings;
        this.live = live;
        this.outbox = outbox;
    }

    /**
     * The token a booking is holding.
     *
     * <p>A booking that has not checked in yet has no token and so nothing to load — 409 rather
     * than 404, because the row exists and the operator is one button away from fixing it: check
     * the customer in, then seat the token (docs/bookings.md §2).
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<PrepaidSeat> findByBooking(long bookingId) {
        BookingSeatLookup.BookedSeat booked = bookings.seatOf(bookingId).orElse(null);
        if (booked == null) {
            return Optional.empty();
        }
        if (booked.queueEntryId() == null) {
            throw new ConflictException(ErrorCode.CONFLICT,
                    "%s has not checked in yet — check the booking in for a token, then seat it"
                            .formatted(booked.name()),
                    Map.of("bookingId", bookingId, "status", booked.status()));
        }
        return Optional.of(seat(lockable(booked.queueEntryId()), booked.memberId()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PrepaidSeat> findByQueueEntry(long queueEntryId) {
        QueueEntry entry = entries.findById(queueEntryId).orElse(null);
        if (entry == null) {
            return Optional.empty();
        }
        requireSeatable(entry);
        return Optional.of(seat(entry, memberBehind(entry)));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(PrepaidSeat seat, long sessionId) {
        QueueEntry entry = entries.findByIdForUpdate(seat.queueEntryId())
                .orElseThrow(() -> new NotFoundException("Queue entry", seat.queueEntryId()));
        // Re-checked under the row lock: the status read while the seat was being resolved is only
        // binding if nobody could seat the same token in between.
        requireSeatable(entry);
        entry.setStatus(QueueEntryStatus.SEATED);
        entry.setSessionId(sessionId);
        outbox.record(SyncOutboxWriter.QUEUE_ENTRIES, SyncOutboxWriter.SEATED, entry.getId(),
                SyncOutboxWriter.data("sessionId", sessionId, "bookingId", entry.getBookingId()));
        live.queueChanged();
        if (entry.getBookingId() != null) {
            bookings.markUsed(entry.getBookingId(), sessionId);
            // The slot has just become USED, which is the last thing the Bookings rail shows about
            // it — announced here rather than in booking, because this is the transaction that did
            // it and §4.5 sends after that one commits.
            live.bookingChanged(entry.getBookingId());
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    private QueueEntry lockable(long queueEntryId) {
        QueueEntry entry = entries.findById(queueEntryId)
                .orElseThrow(() -> new NotFoundException("Queue entry", queueEntryId));
        requireSeatable(entry);
        return entry;
    }

    /**
     * A member rides along only when the token came from a booking that had one attached. A
     * walk-up ticket is sold to whoever is at the counter — {@code queue_entries} keeps no member,
     * so the seat carries none and the operator attaches one on the session if they want to.
     */
    private Long memberBehind(QueueEntry entry) {
        if (entry.getBookingId() == null) {
            return null;
        }
        return bookings.seatOf(entry.getBookingId())
                .map(BookingSeatLookup.BookedSeat::memberId)
                .orElse(null);
    }

    private static PrepaidSeat seat(QueueEntry entry, Long memberId) {
        return new PrepaidSeat(entry.getId(), entry.getBookingId(), entry.getConsoleType(),
                entry.getBlocks(), entry.blockPrice(), entry.getTxId(), memberId);
    }

    /**
     * A token is spent once. Seating one that is already SEATED would load a second helping of
     * prepaid time onto a second console for one payment; seating a REFUNDED one would give away
     * time the venue has already handed the money back for.
     */
    private static void requireSeatable(QueueEntry entry) {
        if (entry.isWaiting()) {
            return;
        }
        throw new ConflictException(ErrorCode.CONFLICT,
                entry.getStatus() == QueueEntryStatus.SEATED
                        ? "TOKEN #%02d has already been seated".formatted(entry.getTokenNo())
                        : "TOKEN #%02d was refunded and removed from the queue"
                                .formatted(entry.getTokenNo()),
                Map.of("queueEntryId", entry.getId(), "status", entry.getStatus().name()));
    }
}
