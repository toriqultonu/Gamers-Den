package dev.gamersden.booking.repo;

import dev.gamersden.booking.domain.Booking;
import dev.gamersden.booking.domain.BookingStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    /**
     * The row locked for the rest of the caller's transaction — how check-in and cancel stop two
     * terminals from acting on the same booking at once. The status guard either side of this read
     * is only binding if nobody else can move the status between the read and the write.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Booking b WHERE b.id = :id")
    Optional<Booking> findByIdForUpdate(@Param("id") long id);

    /** {@code GET /bookings?tab=upcoming} — everything still owed, soonest first. */
    List<Booking> findByStatusOrderByStartAtAsc(BookingStatus status);

    /** {@code GET /bookings?tab=history} — arrived, seated and called-off, most recent first. */
    List<Booking> findByStatusInOrderByStartAtDesc(Collection<BookingStatus> statuses);

    /** The member card's booking strip (api-contract.md, {@code GET /members/{id}}). */
    List<Booking> findByMemberIdOrderByStartAtDesc(Long memberId);

    /**
     * Live bookings on one console whose prepaid slot runs into {@code [from, to)} — the overlap
     * warning at create and the flag on the Upcoming list (docs/bookings.md §7). Cancelled slots
     * are not held by anybody, so they are not a clash.
     *
     * <p>Native because the end of a slot is derived, not stored (invariant §5.4): it is
     * {@code start_at + blocks × 30 min}, and interval arithmetic on a column is something JPQL
     * has no way to spell.
     */
    @Query(value = """
            SELECT * FROM bookings b
            WHERE b.station_id = :stationId
              AND b.status <> 'CANCELLED'
              AND b.start_at < :to
              AND :from < b.start_at + (b.blocks * INTERVAL '30 minutes')
            ORDER BY b.start_at
            """, nativeQuery = true)
    List<Booking> overlapping(@Param("stationId") long stationId,
                              @Param("from") OffsetDateTime from,
                              @Param("to") OffsetDateTime to);
}
