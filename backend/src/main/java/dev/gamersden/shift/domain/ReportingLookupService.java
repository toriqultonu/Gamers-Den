package dev.gamersden.shift.domain;

import dev.gamersden.common.spi.ExpenseLookup;
import dev.gamersden.common.spi.ShiftHistoryLookup;
import dev.gamersden.shift.repo.ExpenseRepository;
import dev.gamersden.shift.repo.ShiftRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The {@code shift} package's answer to {@link ExpenseLookup} and {@link ShiftHistoryLookup} —
 * the only door {@code report} uses into {@code expenses} and {@code shifts}
 * (ARCHITECTURE.md §3).
 *
 * <p>Both interfaces are served here because both are the same package's reporting face and
 * neither is worth a class of its own: petty cash by day, closed tills, and the stretches the
 * venue had a till open at all.
 */
@Service
public class ReportingLookupService implements ExpenseLookup, ShiftHistoryLookup {

    private final ExpenseRepository expenses;
    private final ShiftRepository shifts;

    public ReportingLookupService(ExpenseRepository expenses, ShiftRepository shifts) {
        this.expenses = expenses;
        this.shifts = shifts;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DailyExpense> dailyExpenses(OffsetDateTime from, OffsetDateTime to) {
        return expenses.dailyTotals(from, to).stream()
                .map(row -> new DailyExpense(row.getDay(), row.getTotal()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ClosedShift> recentCloses(int limit) {
        return shifts.findByClosedAtIsNotNullOrderByClosedAtDescIdDesc(PageRequest.of(0, limit))
                .stream()
                .map(shift -> new ClosedShift(shift.getId(), shift.getStaffId(),
                        shift.getTerminal(), shift.getOpenedAt(), shift.getClosedAt(),
                        shift.getOpeningFloat(), shift.getCountedCash(), shift.getExpectedCash(),
                        shift.getDiscrepancy()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TradingSpan> tradingSpans(OffsetDateTime from, OffsetDateTime to) {
        return shifts.overlapping(from, to).stream()
                .map(shift -> new TradingSpan(shift.getOpenedAt(), shift.getClosedAt()))
                .toList();
    }
}
