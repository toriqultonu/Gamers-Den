package dev.gamersden.shift.web;

import dev.gamersden.shift.domain.Expense;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * One petty-cash row — S8's table and what {@code POST /expenses} answers with.
 *
 * @param printJobId the P4 voucher, present only when {@code ?voucher=true} asked for one
 */
@Schema(name = "Expense", description = "A petty-cash payment posted to the shift that made it")
public record ExpenseView(long id,
                          long shiftId,
                          long staffId,
                          String description,
                          String category,
                          int amount,
                          OffsetDateTime createdAt,
                          Long printJobId) {

    public static ExpenseView of(Expense expense) {
        return of(expense, null);
    }

    public static ExpenseView of(Expense expense, Long printJobId) {
        return new ExpenseView(expense.getId(), expense.getShiftId(), expense.getStaffId(),
                expense.getDescription(), expense.getCategory().name(), expense.getAmount(),
                expense.getCreatedAt(), printJobId);
    }
}
