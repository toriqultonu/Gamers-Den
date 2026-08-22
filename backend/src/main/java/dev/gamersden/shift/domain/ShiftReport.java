package dev.gamersden.shift.domain;

import dev.gamersden.common.spi.ShiftReportPrinting;
import dev.gamersden.common.spi.ShiftReportPrinting.Kind;

import java.time.OffsetDateTime;

/**
 * One X or Z report: what a shift took, by method and by category, what it spent, and what that
 * says should be in the drawer.
 *
 * <p>X and Z are the same computation read at different moments (design.md §5: "X: same minus
 * drawer/discrepancy/signature"). The only difference is whether anyone has counted the till —
 * which is why {@link CashCount#counted} is nullable rather than the two being separate shapes,
 * and why an X can be pulled as often as an operator likes without changing anything.
 *
 * @param at        server time the report was computed at (invariant §5.1)
 * @param staffId   whose shift it is, which on a Z is not necessarily who closed it
 */
public record ShiftReport(Kind kind,
                          long shiftId,
                          String terminal,
                          long staffId,
                          OffsetDateTime openedAt,
                          OffsetDateTime closedAt,
                          OffsetDateTime at,
                          int openingFloat,
                          ShiftTakings takings,
                          ExpenseSummary expenses,
                          CashCount cash,
                          String handoverNote) {

    /** What P2/P3 print — the same figures, flattened for the renderer (invariant §5.5). */
    ShiftReportPrinting.ShiftReport asDocument(String deviceId, long operatorId) {
        return new ShiftReportPrinting.ShiftReport(kind, shiftId, terminal, staffId, deviceId,
                operatorId, openedAt, closedAt, at, openingFloat,
                takings.byMethod().stream().map(ShiftReport::line).toList(),
                line(takings.totals()), takings.pointsRedeemed(), takings.pointsEarned(),
                takings.saleCount(), takings.refundCount(),
                expenses.lines().stream()
                        .map(expense -> new ShiftReportPrinting.ExpenseLine(expense.description(),
                                expense.category(), expense.amount()))
                        .toList(),
                expenses.total(), cash.expected(), cash.counted(), cash.discrepancy(), handoverNote);
    }

    private static ShiftReportPrinting.MethodLine line(MethodTakings row) {
        return new ShiftReportPrinting.MethodLine(row.method(), row.gaming(), row.fnb(),
                row.tournament(), row.booking(), row.total());
    }
}
