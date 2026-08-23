package dev.gamersden.booking.domain;

import dev.gamersden.booking.repo.BookingRepository;
import dev.gamersden.common.error.NotFoundException;
import dev.gamersden.common.spi.BookingSeatLookup;
import dev.gamersden.common.spi.QueueTokenLookup;
import dev.gamersden.common.spi.StationArrivalLookup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The {@code booking} package's two doors onto a slot that has arrived: {@link BookingSeatLookup},
 * which {@code queue} reads and writes while a token is being seated, and
 * {@link StationArrivalLookup}, which {@code station} reads to draw the "Seat #NN «name» · 2 h
 * prepaid" prompt on a Floor card (design.md S3).
 *
 * <p>Deliberately separate from {@link BookingService}. This bean is what other packages call
 * <em>into</em>; {@code BookingService} is what calls <em>out</em> to {@code billing} and
 * {@code queue}. Keeping the directions in different beans is what stops the construction cycle —
 * the same shape {@code BookingRegistrationService} already uses for the sale.
 *
 * <p>{@link #markUsed} is {@link Propagation#MANDATORY}: a booking is marked played by the very
 * transaction that creates the session and its prepaid blocks, or not at all (invariant §5.9).
 */
@Service
public class BookingSeatService implements BookingSeatLookup, StationArrivalLookup {

    private static final Logger log = LoggerFactory.getLogger(BookingSeatService.class);

    private final BookingRepository bookings;
    private final QueueTokenLookup tokens;

    public BookingSeatService(BookingRepository bookings, QueueTokenLookup tokens) {
        this.bookings = bookings;
        this.tokens = tokens;
    }

    // ---- BookingSeatLookup --------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Optional<BookedSeat> seatOf(long bookingId) {
        return bookings.findById(bookingId).map(BookingSeatService::seat);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void markUsed(long bookingId, long sessionId) {
        Booking booking = bookings.findById(bookingId)
                .orElseThrow(() -> new NotFoundException("Booking", bookingId));
        booking.setStatus(BookingStatus.USED);
        log.info("booking {} seated on session {} — prepaid {} x 30 min for {}", bookingId,
                sessionId, booking.getBlocks(), booking.getName());
    }

    // ---- StationArrivalLookup -----------------------------------------------------------------

    /**
     * The checked-in customers waiting for their booked console, one per station, in one query for
     * the whole grid.
     *
     * <p>Two bookings can be ARRIVED on the same console at once (docs/bookings.md §7 allows
     * overlapping slots), and a card has room for one prompt: the earliest slot wins, because that
     * is the customer who has been waiting longest. The other is still in the queue rail, and can
     * be seated from there onto any free console of the same type.
     *
     * <p>The token number is joined from {@code queue} rather than stored (invariant §5.4), and an
     * arrival whose token has since been seated or refunded drops out of the map — the card must
     * not offer a seat the token can no longer take.
     */
    @Override
    @Transactional(readOnly = true)
    public Map<Long, Arrival> arrivalsByStation() {
        List<Booking> arrived = bookings.findByStatusOrderByStartAtAsc(BookingStatus.ARRIVED);
        if (arrived.isEmpty()) {
            return Map.of();
        }
        Map<Long, QueueTokenLookup.Token> byToken = tokens.tokensOf(arrived.stream()
                .map(Booking::getQueueEntryId)
                .filter(Objects::nonNull)
                .toList());

        Map<Long, Arrival> byStation = new HashMap<>();
        for (Booking booking : arrived) {
            QueueTokenLookup.Token token = booking.getQueueEntryId() == null
                    ? null
                    : byToken.get(booking.getQueueEntryId());
            if (token == null || !WAITING.equals(token.status())) {
                continue;
            }
            byStation.putIfAbsent(booking.getStationId(), new Arrival(token.queueEntryId(),
                    token.tokenNo(), booking.getName(), booking.getBlocks(), booking.getId()));
        }
        return Map.copyOf(byStation);
    }

    /** {@code queue_entries.status} of a token that can still be seated; a string here (§3). */
    private static final String WAITING = "WAITING";

    private static BookedSeat seat(Booking booking) {
        return new BookedSeat(booking.getId(), booking.getQueueEntryId(), booking.getMemberId(),
                booking.getName(), booking.getStationId(), booking.getStatus().name());
    }
}
