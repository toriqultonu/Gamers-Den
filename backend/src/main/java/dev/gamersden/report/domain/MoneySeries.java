package dev.gamersden.report.domain;

import dev.gamersden.common.spi.ExpenseLookup.DailyExpense;
import dev.gamersden.common.spi.RevenueLookup.DailyRevenue;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Stitches the two grouped reads a money trend is made of — takings from {@code billing}, petty
 * cash from {@code shift} — onto the window's own spine of days.
 *
 * <p>Both reads skip days nothing happened on, and both skip different ones: a day can have
 * expenses and no sales, or the reverse. Zero-filling here is what guarantees the trend array is
 * exactly as long as the window and in date order, which is what a bar chart with a date axis
 * needs and what a chart drawn from a sparse map quietly gets wrong.
 */
public final class MoneySeries {

    private MoneySeries() {
    }

    public static List<DayMoney> of(ReportWindow window,
                                    List<DailyRevenue> takings,
                                    List<DailyExpense> spending) {
        Map<LocalDate, DailyRevenue> byDay = takings.stream()
                .collect(Collectors.toMap(DailyRevenue::day, Function.identity()));
        Map<LocalDate, Integer> spentByDay = spending.stream()
                .collect(Collectors.toMap(DailyExpense::day, DailyExpense::total));
        return window.dates().stream()
                .map(day -> new DayMoney(day, totals(byDay.get(day),
                        spentByDay.getOrDefault(day, 0))))
                .toList();
    }

    private static MoneyTotals totals(DailyRevenue taken, int spent) {
        if (taken == null) {
            return MoneyTotals.ZERO.withExpenses(spent);
        }
        return new MoneyTotals(taken.gaming(), taken.fnb(), taken.tournament(), taken.booking(),
                taken.pointsRedeemed(), taken.revenue(), spent, taken.transactions(),
                taken.sales());
    }
}
