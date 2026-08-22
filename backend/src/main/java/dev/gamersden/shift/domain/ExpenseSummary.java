package dev.gamersden.shift.domain;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The petty-cash block of the X/Z report (design.md P2): every expense posted to the shift, its
 * category totals, and what it all came to.
 *
 * <p>Expenses are money out of the drawer, so this figure is subtracted when the close works out
 * what should be in it. They are listed as well as summed because the operator counting the till
 * needs to see the vouchers they are missing, not just the number they are short by.
 */
public record ExpenseSummary(int total, int count, List<CategoryTotal> byCategory,
                             List<ExpenseLine> lines) {

    public ExpenseSummary {
        byCategory = List.copyOf(byCategory);
        lines = List.copyOf(lines);
    }

    public static ExpenseSummary of(List<Expense> expenses) {
        Map<String, Integer> byCategory = new LinkedHashMap<>();
        int total = 0;
        for (Expense expense : expenses) {
            byCategory.merge(expense.getCategory().name(), expense.getAmount(), Integer::sum);
            total += expense.getAmount();
        }
        return new ExpenseSummary(total, expenses.size(),
                byCategory.entrySet().stream()
                        .map(entry -> new CategoryTotal(entry.getKey(), entry.getValue()))
                        .toList(),
                expenses.stream().map(ExpenseLine::of).toList());
    }

    public record CategoryTotal(String category, int amount) {
    }

    public record ExpenseLine(long id, String description, String category, int amount,
                              OffsetDateTime at) {

        static ExpenseLine of(Expense expense) {
            return new ExpenseLine(expense.getId(), expense.getDescription(),
                    expense.getCategory().name(), expense.getAmount(), expense.getCreatedAt());
        }
    }
}
