package dev.gamersden.common.spi;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The narrow write the {@code shift} package needs from {@code printing} — P2 (Z) and P3 (X) —
 * without reaching for {@code PrintJobRepository} (ARCHITECTURE.md §3).
 *
 * <p>Same two invariants as the sale ticket. The Z job is created <em>inside</em> the transaction
 * that closes the shift (§5.3), so a shift cannot be closed without its report and a rolled-back
 * close leaves no paper behind; and the bytes are rendered once, here, and stored (§5.5) — which
 * matters more for a Z than for anything else, since the figures on it are a snapshot of a moment
 * that will never be recomputable.
 *
 * <p>What is passed is the report's content, not its layout. Until B17 the renderer behind this
 * door writes plain text; swapping in the real ESC/POS templates changes nothing on this side.
 */
public interface ShiftReportPrinting {

    /** Renders and queues one X or Z report, in the caller's transaction. */
    long issueShiftReport(ShiftReport report);

    /** X is the interim read; Z is the close, and the only one that counts the drawer. */
    enum Kind {
        X,
        Z
    }

    /**
     * Everything P2/P3 print (design.md §5): shift id, opened/closed, operator, float, takings
     * method × category, expected vs counted vs discrepancy, expenses, counts.
     *
     * @param deviceId     which printer the job is queued for — the terminal owns its USB printer
     * @param operatorId   who asked for it, for the print-job audit; on a Z that may be a manager
     *                     closing someone else's shift, so {@code shiftStaffId} is printed instead
     * @param expectedCash {@code null} on an X — the interim report deliberately omits the drawer
     */
    record ShiftReport(Kind kind,
                       long shiftId,
                       String terminal,
                       long shiftStaffId,
                       String deviceId,
                       long operatorId,
                       OffsetDateTime openedAt,
                       OffsetDateTime closedAt,
                       OffsetDateTime at,
                       int openingFloat,
                       List<MethodLine> byMethod,
                       MethodLine totals,
                       int pointsRedeemed,
                       int pointsEarned,
                       int saleCount,
                       int refundCount,
                       List<ExpenseLine> expenses,
                       int expensesTotal,
                       Integer expectedCash,
                       Integer countedCash,
                       Integer discrepancy,
                       String handoverNote) {

        public ShiftReport {
            byMethod = List.copyOf(byMethod);
            expenses = List.copyOf(expenses);
        }
    }

    /**
     * One row of the takings matrix: what a method took, split across the categories it paid for.
     * {@code method} is {@code "TOTAL"} on the summary line.
     */
    record MethodLine(String method, int gaming, int fnb, int tournament, int booking, int total) {
    }

    /** One petty-cash line on the report's expense block. */
    record ExpenseLine(String description, String category, int amount) {
    }
}
