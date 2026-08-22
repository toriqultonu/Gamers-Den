package dev.gamersden.shift.web;

import dev.gamersden.common.security.Roles;
import dev.gamersden.shift.domain.ExpenseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * {@code /expenses} — S8 (api-contract.md, "Shifts &amp; expenses"). Every operator records petty
 * cash; the matrix ticks "Expenses, shift open/close" for all three roles.
 *
 * <p>{@code ?voucher=true} adds the P4 slip, rendered and queued in the same transaction that
 * recorded the row (invariant §5.3) — there is no way to end up with a signed voucher for an
 * expense that was never written, or an expense whose voucher silently failed to render.
 */
@RestController
@RequestMapping("/expenses")
@Tag(name = "Shifts & expenses")
public class ExpenseController {

    private final ExpenseService expenses;

    public ExpenseController(ExpenseService expenses) {
        this.expenses = expenses;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "Record a petty-cash payment",
            description = "Posted to the open shift on this terminal, where it subtracts from the "
                    + "expected drawer. 409 when no shift is open — money cannot leave a till "
                    + "nobody has opened. voucher=true also queues the P4 slip.")
    public ExpenseView record(@Valid @RequestBody CreateExpenseRequest request,
                              @RequestParam(defaultValue = "false") boolean voucher) {
        ExpenseService.RecordedExpense recorded = expenses.record(request.description(),
                request.category(), request.amount(), voucher);
        return ExpenseView.of(recorded.expense(), recorded.printJobId());
    }

    @GetMapping
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "A shift's petty cash",
            description = "Newest first. Defaults to the open shift on this terminal; pass "
                    + "shiftId to read a closed one back.")
    public List<ExpenseView> list(@RequestParam(required = false) Long shiftId) {
        return expenses.of(shiftId).stream().map(ExpenseView::of).toList();
    }
}
