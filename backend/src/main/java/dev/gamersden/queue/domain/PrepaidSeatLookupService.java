package dev.gamersden.queue.domain;

import dev.gamersden.common.spi.PrepaidSeatLookup;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * The {@code queue} package's answer to {@link PrepaidSeatLookup}. {@code queue_entries} and
 * {@code bookings} arrive in V003 with B15/B16, so every token reads as unknown for now and
 * {@code POST /sessions} with a {@code bookingId} or {@code queueEntryId} answers 404.
 *
 * <p>The session side of the seat transaction — prepaid blocks born paid with the sale's
 * {@code paid_tx_id}, the {@code CONSOLE_TYPE_MISMATCH} guard, {@code sessions.queue_entry_id} —
 * is already implemented in {@code SessionService}. B16 fills these three methods in and the seat
 * flow works without touching the session package.
 */
@Service
public class PrepaidSeatLookupService implements PrepaidSeatLookup {

    @Override
    public Optional<PrepaidSeat> findByBooking(long bookingId) {
        return Optional.empty();
    }

    @Override
    public Optional<PrepaidSeat> findByQueueEntry(long queueEntryId) {
        return Optional.empty();
    }

    @Override
    public void consume(PrepaidSeat seat, long sessionId) {
        // B16: queue entry -> SEATED, booking -> USED, inside the caller's transaction.
    }
}
