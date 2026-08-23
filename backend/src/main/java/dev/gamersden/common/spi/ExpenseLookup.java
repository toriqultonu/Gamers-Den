package dev.gamersden.common.spi;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The narrow read the {@code report} package needs from {@code shift} — petty cash folded by the
 * venue day it was spent on — without reaching for {@code ExpenseRepository} (ARCHITECTURE.md §3).
 *
 * <p>Net profit is takings less expenses everywhere it appears (design.md S2/S9), so this is the
 * second half of every profit figure the two screens show.
 */
public interface ExpenseLookup {

    /** One row per venue day that had petty cash, oldest first; quiet days are absent. */
    List<DailyExpense> dailyExpenses(OffsetDateTime from, OffsetDateTime to);

    record DailyExpense(LocalDate day, int total) {
    }
}
