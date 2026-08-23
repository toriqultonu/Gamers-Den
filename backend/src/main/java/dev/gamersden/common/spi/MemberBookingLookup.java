package dev.gamersden.common.spi;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The narrow read the {@code member} package needs from {@code booking} — the bookings strip the
 * contract promises on {@code GET /members/{id}} — without reaching for {@code BookingRepository}
 * (ARCHITECTURE.md §3: no cross-package repository access, call the owning package's service).
 *
 * <p>Implemented by {@code booking/domain/BookingService}. Read-only, and everything derived —
 * the total, the end of the slot — is computed at read time from the booking's own snapshots
 * (invariant §5.4).
 */
public interface MemberBookingLookup {

    /** The member's bookings, most recent slot first, at most {@code limit} of them. */
    List<MemberBooking> recentBookings(long memberId, int limit);

    /**
     * One row of the bookings strip.
     *
     * @param status {@code PAID|ARRIVED|USED|CANCELLED}
     * @param total  play time plus the package fee, both as snapshotted at sale
     */
    record MemberBooking(long bookingId,
                         long stationId,
                         String stationName,
                         OffsetDateTime startAt,
                         int blocks,
                         int total,
                         String status,
                         Integer tokenNo) {
    }
}
