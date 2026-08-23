package dev.gamersden.booking.web;

import dev.gamersden.booking.domain.BookingService;
import dev.gamersden.common.events.LiveChange;
import dev.gamersden.common.events.LiveEvent;
import dev.gamersden.common.events.SseHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The SSE {@code booking-update} event (ARCHITECTURE.md §4.5) — one slot, in the shape
 * {@code GET /bookings/{id}} answers with, sent after the transaction that moved it commits.
 *
 * <p>Re-read rather than described by the caller, because half of what the Bookings rail renders
 * is derived at read time and none of it is stored (invariant §5.4): the token the check-in just
 * issued, whether the slot is still cancellable against the server clock, and the overlap warning.
 * A payload built inside the write would be missing exactly the parts that move.
 */
@Component
public class BookingLiveEmitter {

    private static final Logger log = LoggerFactory.getLogger(BookingLiveEmitter.class);

    private final BookingService bookings;
    private final SseHub hub;

    public BookingLiveEmitter(BookingService bookings, SseHub hub) {
        this.bookings = bookings;
        this.hub = hub;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onBookingChanged(LiveChange.BookingChanged changed) {
        if (!hub.hasSubscribers()) {
            return;
        }
        try {
            hub.publish(LiveEvent.BOOKING_UPDATE,
                    BookingView.of(bookings.get(changed.bookingId())));
        } catch (RuntimeException ex) {
            log.warn("booking-update for booking {} not sent: {}", changed.bookingId(), ex.toString());
        }
    }
}
