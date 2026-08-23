package dev.gamersden.booking.repo;

import dev.gamersden.booking.domain.Booking;
import dev.gamersden.booking.domain.BookingStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
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

    /**
     * Slots due to start inside the window, folded by the venue day they were due on — S9's
     * "bookings per day" and the show-rate's three counters (docs/bookings.md §6).
     *
     * <p>Keyed on {@code start_at}, not {@code created_at}: this is attendance, and a booking
     * sold on Monday for Tuesday was a Tuesday slot. {@code expired} is the no-show — still PAID
     * with its slot already past — measured against a server-supplied {@code now}, never a
     * client's clock (invariant §5.1). ARRIVED sits in neither half of the show-rate: they turned
     * up, and the visit has not resolved either way until the Floor seats them.
     */
    @Query(value = """
            SELECT (b.start_at AT TIME ZONE 'Asia/Dhaka')::date              AS day,
                   COUNT(*)                                                  AS booked,
                   COUNT(*) FILTER (WHERE b.status = 'USED')                 AS used,
                   COUNT(*) FILTER (WHERE b.status = 'CANCELLED')            AS cancelled,
                   COUNT(*) FILTER (WHERE b.status = 'ARRIVED')              AS arrived,
                   COUNT(*) FILTER (WHERE b.status = 'PAID'
                                      AND b.start_at < :now)                 AS expired
              FROM bookings b
             WHERE b.start_at >= :from AND b.start_at < :to
             GROUP BY day
             ORDER BY day
            """, nativeQuery = true)
    List<DailyBookingRow> dailyAttendance(@Param("from") OffsetDateTime from,
                                          @Param("to") OffsetDateTime to,
                                          @Param("now") OffsetDateTime now);

    /**
     * What bookings sold inside the window brought in, split into the play time and the package
     * fee docs/bookings.md §6 asks reports to show separately.
     *
     * <p>Keyed on {@code created_at} — the day the money was taken, so the figure lines up with
     * the {@code transactions.booking_amount} it came in as. CANCELLED bookings are left out
     * because their refund gave every taka of it back.
     */
    @Query(value = """
            SELECT COUNT(*)                          AS bookings,
                   COALESCE(SUM(b.play_amount), 0)   AS "playAmount",
                   COALESCE(SUM(b.package_fee), 0)   AS "packageFee"
              FROM bookings b
             WHERE b.created_at >= :from AND b.created_at < :to
               AND b.status <> 'CANCELLED'
            """, nativeQuery = true)
    BookingMoneyRow incomeBetween(@Param("from") OffsetDateTime from,
                                  @Param("to") OffsetDateTime to);

    /** Bookings still PAID — the booking half of S2's pre-sold stat (docs/bookings.md §6). */
    @Query(value = """
            SELECT COUNT(*)                          AS bookings,
                   COALESCE(SUM(b.play_amount), 0)   AS "playAmount",
                   COALESCE(SUM(b.package_fee), 0)   AS "packageFee"
              FROM bookings b
             WHERE b.status = 'PAID'
            """, nativeQuery = true)
    BookingMoneyRow preSold();

    /** Projection for {@link #dailyAttendance}. */
    interface DailyBookingRow {
        LocalDate getDay();

        int getBooked();

        int getUsed();

        int getCancelled();

        int getArrived();

        int getExpired();
    }

    /** Projection for {@link #incomeBetween} and {@link #preSold}. */
    interface BookingMoneyRow {
        int getBookings();

        int getPlayAmount();

        int getPackageFee();
    }
}
