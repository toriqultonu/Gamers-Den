package dev.gamersden.shift.web;

import dev.gamersden.shift.domain.ExpenseCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /expenses} (api-contract.md, "Shifts &amp; expenses").
 *
 * <p>No shift field: petty cash comes out of the drawer that is open on the caller's terminal, and
 * that is what makes it subtract from the right Z (invariant §5.7).
 */
@Schema(name = "CreateExpenseRequest", description = "Record a petty-cash payment against the "
        + "open shift")
public record CreateExpenseRequest(@NotBlank @Size(max = 200) String description,
                                   @NotNull ExpenseCategory category,
                                   @NotNull @Positive Integer amount) {
}
