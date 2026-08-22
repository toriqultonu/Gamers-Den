package dev.gamersden.shift.domain;

import dev.gamersden.common.config.VenueTime;
import dev.gamersden.common.error.ValidationFailedException;
import dev.gamersden.common.security.CurrentStaff;
import dev.gamersden.common.security.StaffPrincipal;
import dev.gamersden.common.spi.ExpenseVoucherPrinting;
import dev.gamersden.common.util.Money;
import dev.gamersden.shift.repo.ExpenseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/**
 * Petty cash (api-contract.md, "Shifts &amp; expenses"): {@code GET/POST /expenses}, with
 * {@code ?voucher=true} adding the P4 slip someone signs for the money.
 *
 * <p>An expense always belongs to the shift that paid it. There is no "which shift?" parameter on
 * the write for the same reason there is none on a payment: the drawer it comes out of is the one
 * that is open, and that is what makes the Z's expected-cash line add up (invariant §5.7). Money
 * cannot leave a till nobody has opened, so a write with no shift open is refused outright.
 *
 * <p>The voucher is rendered and queued inside the recording transaction (§5.3) — the slip and the
 * row it is signed against are written together or not at all.
 */
@Service
public class ExpenseService {

    private static final Logger log = LoggerFactory.getLogger(ExpenseService.class);

    private final ExpenseRepository expenses;
    private final ShiftService shifts;
    private final ExpenseVoucherPrinting vouchers;
    private final Clock clock;

    public ExpenseService(ExpenseRepository expenses,
                          ShiftService shifts,
                          ExpenseVoucherPrinting vouchers,
                          Clock clock) {
        this.expenses = expenses;
        this.shifts = shifts;
        this.vouchers = vouchers;
        this.clock = clock;
    }

    /**
     * Records one petty-cash payment against the open shift.
     *
     * @param voucher whether to print P4 as well
     */
    @Transactional
    public RecordedExpense record(String description, ExpenseCategory category, int amount,
                                  boolean voucher) {
        StaffPrincipal staff = CurrentStaff.require();
        if (amount <= 0) {
            throw ValidationFailedException.onField("amount", "An expense must be above zero");
        }
        Shift shift = shifts.current(staff.terminal());
        Expense expense = expenses.saveAndFlush(new Expense(shift.getId(), staff.id(),
                description.trim(), category, amount));

        Long printJobId = voucher
                ? vouchers.issueExpenseVoucher(new ExpenseVoucherPrinting.ExpenseVoucher(
                        expense.getId(), expense.getDescription(), category.name(), amount,
                        staff.terminal(), staff.id(), shift.getId(), VenueTime.now(clock)))
                : null;
        log.info("expense {} recorded on shift {} by staff {}: {} {} \"{}\"{}",
                expense.getId(), shift.getId(), staff.id(), Money.format(amount), category,
                expense.getDescription(),
                printJobId == null ? "" : " — voucher print job " + printJobId);
        return new RecordedExpense(expense, printJobId);
    }

    /** The petty-cash table on S8: one shift's expenses, newest first. */
    @Transactional(readOnly = true)
    public List<Expense> of(Long shiftId) {
        long shift = shiftId != null
                ? shiftId
                : shifts.current(CurrentStaff.require().terminal()).getId();
        return expenses.findByShiftIdOrderByIdDesc(shift);
    }

    /** An expense and the voucher job it asked for, when it did. */
    public record RecordedExpense(Expense expense, Long printJobId) {
    }
}
