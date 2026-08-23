package dev.gamersden.billing.repo;

import dev.gamersden.billing.domain.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByPublicId(String publicId);

    List<Transaction> findByShiftId(Long shiftId);

    /** A shift's postings in the order they were taken — the X/Z report's input (B11). */
    List<Transaction> findByShiftIdOrderByIdAsc(Long shiftId);

    /**
     * How many public ids the venue day has already handed out. Counted over {@code public_id}
     * rather than {@code created_at} on purpose: the id <em>is</em> the sequence, so the number is
     * derived from the same column it has to be unique in, and a row inserted with the database's
     * {@code now()} can never be counted against a different day than the one it is named for.
     */
    @Query("SELECT count(t) FROM Transaction t WHERE t.publicId LIKE :prefix")
    long countWithPublicIdPrefix(@Param("prefix") String prefix);

    /**
     * Serialises public-id allocation for the rest of the caller's transaction. Two settles racing
     * for the same day's next number must queue here, or both would read the same count and one
     * would lose at the UNIQUE index — after having already written its money rows.
     *
     * <p>A transaction-scoped advisory lock rather than a counter table: nothing in the documented
     * schema counts transactions (ARCHITECTURE.md §4.1), and {@code token_seq} is the daily
     * <em>queue</em> counter shared by bookings and play tickets (invariant §5.10), not this.
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(CAST(:key AS bigint))) AS held",
            nativeQuery = true)
    int lockPublicIdSequence(@Param("key") long key);

    /**
     * Money folded by the venue day it was taken on — S2's 30-day trend and S9's 14-day stacked
     * bars (TASKLIST B20: read-only SQL aggregates, no stored rollups).
     *
     * <p>The window is bounded by instants so the {@code created_at} index does the narrowing, and
     * only the surviving rows are re-expressed in {@code Asia/Dhaka} to be grouped: a day is the
     * venue's day, never UTC's (invariant §5.1). Nothing is filtered — a void's reversal is a real
     * negative posting and belongs to the day it was given (invariant §5.7).
     */
    @Query(value = """
            SELECT (t.created_at AT TIME ZONE 'Asia/Dhaka')::date       AS day,
                   COALESCE(SUM(t.gaming_amount), 0)                    AS gaming,
                   COALESCE(SUM(t.fnb_amount), 0)                       AS fnb,
                   COALESCE(SUM(t.tournament_amount), 0)                AS tournament,
                   COALESCE(SUM(t.booking_amount), 0)                   AS booking,
                   COALESCE(SUM(t.points_redeemed), 0)                  AS "pointsRedeemed",
                   COALESCE(SUM(t.total_due), 0)                        AS revenue,
                   COUNT(*)                                             AS transactions,
                   COUNT(*) FILTER (WHERE t.total_due > 0)              AS sales
              FROM transactions t
             WHERE t.created_at >= :from AND t.created_at < :to
             GROUP BY day
             ORDER BY day
            """, nativeQuery = true)
    List<DailyRevenueRow> dailyRevenue(@Param("from") OffsetDateTime from,
                                       @Param("to") OffsetDateTime to);

    /** The same window folded by venue hour-of-day — S9's busiest-hours table. */
    @Query(value = """
            SELECT EXTRACT(HOUR FROM (t.created_at AT TIME ZONE 'Asia/Dhaka'))::int AS hour,
                   COALESCE(SUM(t.total_due), 0)                                    AS revenue,
                   COUNT(*) FILTER (WHERE t.total_due > 0)                          AS sales
              FROM transactions t
             WHERE t.created_at >= :from AND t.created_at < :to
             GROUP BY hour
             ORDER BY hour
            """, nativeQuery = true)
    List<HourlyRevenueRow> hourlyRevenue(@Param("from") OffsetDateTime from,
                                         @Param("to") OffsetDateTime to);

    /**
     * The carts a sale that stuck settled in the window — the input to S9's top-seller table.
     *
     * <p>Both halves of a void are dropped here, and only here: the original because it was
     * reversed, the reversal because {@code total_due < 0}. A top-seller table counts what left
     * the counter, and a reversed sale left nothing.
     */
    @Query(value = """
            SELECT t.cart_id
              FROM transactions t
             WHERE t.cart_id IS NOT NULL
               AND NOT t.voided
               AND t.total_due > 0
               AND t.created_at >= :from AND t.created_at < :to
            """, nativeQuery = true)
    List<Long> settledCartIds(@Param("from") OffsetDateTime from,
                              @Param("to") OffsetDateTime to);

    /** What each of those shifts took — the total beside every close in S2's shift rail. */
    @Query(value = """
            SELECT t.shift_id                     AS "shiftId",
                   COALESCE(SUM(t.total_due), 0)  AS revenue
              FROM transactions t
             WHERE t.shift_id IN (:shiftIds)
             GROUP BY t.shift_id
            """, nativeQuery = true)
    List<ShiftTakingsRow> takingsByShift(@Param("shiftIds") Collection<Long> shiftIds);

    /** Projection for {@link #dailyRevenue}; money is integer BDT throughout. */
    interface DailyRevenueRow {
        LocalDate getDay();

        int getGaming();

        int getFnb();

        int getTournament();

        int getBooking();

        int getPointsRedeemed();

        int getRevenue();

        int getTransactions();

        int getSales();
    }

    /** Projection for {@link #hourlyRevenue}. */
    interface HourlyRevenueRow {
        int getHour();

        int getRevenue();

        int getSales();
    }

    /** Projection for {@link #takingsByShift}. */
    interface ShiftTakingsRow {
        long getShiftId();

        int getRevenue();
    }
}
