package dev.gamersden.common.spi;

import java.util.Optional;

/**
 * The narrow read-and-write the {@code queue} package needs from {@code booking} while a token is
 * being seated — the booking half of {@link PrepaidSeatLookup} (ARCHITECTURE.md §3).
 *
 * <p>{@code queue} owns the token, {@code booking} owns the slot the token was issued for, and the
 * seat transaction has to move both. This is the door for the second half: read which booking a
 * token belongs to, then flip it to USED inside the same transaction that created the session
 * (invariant §5.9).
 *
 * <p>{@link #markUsed} is {@link org.springframework.transaction.annotation.Propagation#MANDATORY}
 * on the implementation — a booking marked played by a session that rolled back would be a
 * customer charged for time they never got.
 */
public interface BookingSeatLookup {

    /** The booking behind a {@code bookingId}, or empty when the id is unknown. */
    Optional<BookedSeat> seatOf(long bookingId);

    /** Moves the booking to USED, in the caller's transaction. */
    void markUsed(long bookingId, long sessionId);

    /**
     * @param queueEntryId the token issued at check-in, or {@code null} while the booking is still
     *                     PAID — a slot is seated through its token, so an un-checked-in booking
     *                     has nothing to load yet
     * @param status       {@code PAID|ARRIVED|USED|CANCELLED} as a string, so {@code common} stays
     *                     free of the {@code booking} enum
     */
    record BookedSeat(long bookingId,
                      Long queueEntryId,
                      Long memberId,
                      String name,
                      long stationId,
                      String status) {
    }
}
