package dev.gamersden.common.spi;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The narrow read the {@code report} package needs from {@code shift} — the recent closes S2
 * lists, and the hours the venue was actually trading — without reaching for
 * {@code ShiftRepository} (ARCHITECTURE.md §3).
 *
 * <p>Trading time is what station utilisation is a percentage <em>of</em>. A console idle at 4am
 * is not under-used, it is shut; measuring against the wall clock would report every venue as
 * mostly empty. An open shift is the venue's own record of "we were trading", so the shifts that
 * overlap a window, merged, are the honest denominator.
 */
public interface ShiftHistoryLookup {

    /** The last {@code limit} closed shifts, newest close first. */
    List<ClosedShift> recentCloses(int limit);

    /**
     * Every shift that overlaps the window, oldest first — open ones included, with
     * {@code closedAt} still null. Overlapping and duplicated stretches are the caller's to merge:
     * two terminals trading at once is one open venue, not two.
     */
    List<TradingSpan> tradingSpans(OffsetDateTime from, OffsetDateTime to);

    /**
     * One closed till.
     *
     * @param discrepancy counted less expected; negative is short, and the close that wrote it
     *                    also wrote the alert S2's rail shows
     */
    record ClosedShift(long shiftId,
                       long staffId,
                       String terminal,
                       OffsetDateTime openedAt,
                       OffsetDateTime closedAt,
                       int openingFloat,
                       Integer countedCash,
                       Integer expectedCash,
                       Integer discrepancy) {
    }

    /** @param closedAt null while the shift is still open — the caller clips it at "now" */
    record TradingSpan(OffsetDateTime openedAt, OffsetDateTime closedAt) {
    }
}
