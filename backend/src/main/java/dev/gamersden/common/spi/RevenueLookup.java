package dev.gamersden.common.spi;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The narrow read the {@code report} package needs from {@code billing} — money already posted,
 * folded by the venue day and the venue hour it was taken in — without reaching for
 * {@code TransactionRepository} (ARCHITECTURE.md §3).
 *
 * <p>Sums cross this door, not rows. The X/Z matrix asks for postings because it reconciles two
 * axes that live on two tables ({@link ShiftTakingsLookup}); a report asks for totals over weeks,
 * so the grouping is the database's job and nothing but the answer is carried into memory
 * (TASKLIST B20: read-only SQL aggregates, no stored rollups).
 *
 * <p>Nothing is filtered out, for the same reason the X/Z filters nothing: a voided sale stays
 * exactly as it was printed and its reversal is a second, negative row (invariant §5.7). Summing
 * everything in a window is what makes a report agree with the drawers that were counted inside
 * it. Refunds therefore lower revenue on the day they were <em>given</em>, not on the day the
 * original sale was taken.
 */
public interface RevenueLookup {

    /**
     * One row per venue day that saw money move, oldest first. Days with no transactions are
     * absent — the caller decides whether a gap renders as a zero bar or as "no data".
     *
     * @param from inclusive lower bound, an instant so the query rides the {@code created_at} index
     * @param to   exclusive upper bound
     */
    List<DailyRevenue> dailyRevenue(OffsetDateTime from, OffsetDateTime to);

    /** The same window folded by venue hour-of-day (0–23); hours nobody sold in are absent. */
    List<HourlyRevenue> hourlyRevenue(OffsetDateTime from, OffsetDateTime to);

    /**
     * The carts a real sale settled in the window — the F&amp;B side of "top sellers", handed to
     * {@code catalog} to price out its own lines.
     *
     * <p>Voided sales and their negative reversals are both left out here, deliberately and
     * unlike everywhere else in this interface: a top-seller table counts what customers took
     * away, and a reversed sale left nothing on the counter.
     */
    List<Long> settledCartIds(OffsetDateTime from, OffsetDateTime to);

    /** What each of those shifts took, keyed by shift id; shifts that took nothing are absent. */
    Map<Long, Integer> takingsByShift(Collection<Long> shiftIds);

    /**
     * A venue day's money.
     *
     * <p>The four buckets are gross — they are the transaction's own snapshot of what was sold —
     * so they add up to {@code revenue + pointsRedeemed}. {@code revenue} is what was actually
     * tendered, and is the figure net profit is measured from.
     *
     * @param transactions rows posted that day, refunds and reversals included
     * @param sales        the subset that took money in ({@code totalDue > 0}) — the denominator
     *                     of an average ticket, which a refund must not drag down twice
     */
    record DailyRevenue(LocalDate day,
                        int gaming,
                        int fnb,
                        int tournament,
                        int booking,
                        int pointsRedeemed,
                        int revenue,
                        int transactions,
                        int sales) {
    }

    /** @param hour venue-local hour-of-day, 0–23 */
    record HourlyRevenue(int hour, int revenue, int sales) {
    }
}
