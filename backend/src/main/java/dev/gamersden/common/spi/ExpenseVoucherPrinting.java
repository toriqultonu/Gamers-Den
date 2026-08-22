package dev.gamersden.common.spi;

import java.time.OffsetDateTime;

/**
 * The narrow write the {@code shift} package needs from {@code printing} — P4, the petty-cash
 * voucher a {@code POST /expenses?voucher=true} asks for — without reaching for
 * {@code PrintJobRepository} (ARCHITECTURE.md §3).
 *
 * <p>Created in the transaction that recorded the expense (§5.3): the slip someone signs and the
 * row it is signed against are written together or not at all.
 */
public interface ExpenseVoucherPrinting {

    /** Renders and queues one expense voucher, in the caller's transaction. */
    long issueExpenseVoucher(ExpenseVoucher voucher);

    /**
     * Everything P4 prints (design.md §5): date-time, description, category, amount, recorded-by,
     * signature line.
     */
    record ExpenseVoucher(long expenseId,
                          String description,
                          String category,
                          int amount,
                          String deviceId,
                          long operatorId,
                          long shiftId,
                          OffsetDateTime at) {
    }
}
