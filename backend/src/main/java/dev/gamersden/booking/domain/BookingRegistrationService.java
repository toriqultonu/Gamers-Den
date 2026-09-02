package dev.gamersden.booking.domain;

import dev.gamersden.booking.repo.BookingRepository;
import dev.gamersden.common.spi.BookingSale;
import dev.gamersden.common.spi.SyncOutboxWriter;
import dev.gamersden.common.spi.BookingSettlement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The {@code booking} package's answer to {@link BookingSettlement} — the row {@code billing}
 * writes through while it is taking the money for a booking.
 *
 * <p>Deliberately separate from {@link BookingService}. This bean is what {@code billing} calls
 * <em>into</em> during a sale; {@code BookingService} is what calls <em>out</em> to {@code billing}
 * to sell and to refund. Keeping the two directions in different beans is what stops the two
 * packages from forming a construction cycle — the same shape {@code tournament} uses for entries.
 *
 * <p>{@link Propagation#MANDATORY} makes pay-first structural: there is no code path that writes a
 * booking outside the transaction that took its money, and {@code bookings.tx_id} being
 * {@code NOT NULL} makes it unforgeable (invariant §5.7, docs/bookings.md §2).
 */
@Service
public class BookingRegistrationService implements BookingSettlement {

    private static final Logger log = LoggerFactory.getLogger(BookingRegistrationService.class);

    private final BookingRepository bookings;
    private final SyncOutboxWriter outbox;

    public BookingRegistrationService(BookingRepository bookings, SyncOutboxWriter outbox) {
        this.bookings = bookings;
        this.outbox = outbox;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Registered register(long txId, BookingSale.Order order) {
        List<Long> clashes = overlapsOf(order);
        Booking booking = bookings.saveAndFlush(new Booking(order.stationId(), order.memberId(),
                order.name(), order.phone(), order.startAt(), order.blocks(), order.playAmount(),
                order.packageFee(), order.cutoffHours(), txId));
        log.info("booking {} held {} on station {} from {} for {} x 30 min — play {} + fee {} = {} "
                        + "BDT on transaction {}, cancellable until {}{}", booking.getId(),
                booking.getName(), booking.getStationId(), booking.getStartAt(), booking.getBlocks(),
                booking.getPlayAmount(), booking.getPackageFee(), booking.total(), txId,
                booking.cancellableUntil(),
                clashes.isEmpty() ? "" : " (overlaps bookings " + clashes + ")");
        outbox.record(SyncOutboxWriter.BOOKINGS, SyncOutboxWriter.CREATED, booking.getId(),
                SyncOutboxWriter.data(
                        "stationId", booking.getStationId(),
                        "memberId", booking.getMemberId(),
                        "name", booking.getName(),
                        "startAt", booking.getStartAt().toString(),
                        "blocks", booking.getBlocks(),
                        "playAmount", booking.getPlayAmount(),
                        "packageFee", booking.getPackageFee(),
                        "cutoffHours", booking.getCutoffHours(),
                        "txId", txId));
        return new Registered(booking.getId(), clashes);
    }

    /**
     * The overlap <em>warning</em> of docs/bookings.md §7: two bookings on one console at the same
     * time are allowed, because the floor routinely sorts that out by seating the token on another
     * console of the same type. Refusing would only teach staff to book the wrong console. So the
     * clash is reported, never thrown — the operator confirming the form is the override.
     */
    private List<Long> overlapsOf(BookingSale.Order order) {
        OffsetDateTime endAt = order.startAt().plusMinutes((long) order.blocks() * Booking.BLOCK_MINUTES);
        return bookings.overlapping(order.stationId(), order.startAt(), endAt).stream()
                .map(Booking::getId)
                .toList();
    }
}
