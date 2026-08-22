package dev.gamersden.shift.web;

import dev.gamersden.shift.domain.CashCount;
import dev.gamersden.shift.domain.ExpenseSummary;
import dev.gamersden.shift.domain.MethodTakings;
import dev.gamersden.shift.domain.ShiftReport;
import dev.gamersden.shift.domain.ShiftTakings;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The X/Z contract — what S7 renders and what P2/P3 print (api-contract.md, "Shifts &amp;
 * expenses"; design.md §5).
 *
 * <p>One shape serves both reports. An X is a Z that nobody has counted yet: {@code kind} says
 * which, and {@code cash.counted} / {@code cash.discrepancy} are simply absent on an interim read
 * (they are omitted rather than sent as null — {@code default-property-inclusion: non_null}).
 * {@code cash.expected} is present on both, because S7's drawer field shows a live discrepancy
 * while the operator types; only the printed X omits it.
 *
 * <p>Two guarantees the screen is built on:
 *
 * <ul>
 *   <li>Each {@code byMethod} row's four categories sum to its {@code total}, and the rows sum to
 *       {@code totals} — the matrix always reconciles, because every tender is attributed whole.</li>
 *   <li>{@code cash.expected == cash.openingFloat + cash.takings - cash.expenses}, where
 *       {@code cash.takings} is the CASH row's total. Nothing else touches the drawer.</li>
 * </ul>
 *
 * @param serverTime when this was computed; the FE never derives it from the local clock (§5.1)
 * @param printJobId the job that printed it, when one was asked for ({@code ?print=true}, or the
 *                   Z a close always produces)
 */
@Schema(name = "ShiftReport", description = "A shift's takings by method and category, its petty "
        + "cash, and what that says should be in the drawer")
public record ShiftReportView(String kind,
                              long shiftId,
                              String terminal,
                              long staffId,
                              OffsetDateTime openedAt,
                              OffsetDateTime closedAt,
                              OffsetDateTime serverTime,
                              int openingFloat,
                              TakingsView takings,
                              ExpensesView expenses,
                              CashView cash,
                              String handoverNote,
                              Long printJobId) {

    public static ShiftReportView of(ShiftReport report, Long printJobId) {
        return new ShiftReportView(report.kind().name(), report.shiftId(), report.terminal(),
                report.staffId(), report.openedAt(), report.closedAt(), report.at(),
                report.openingFloat(), TakingsView.of(report.takings()),
                ExpensesView.of(report.expenses()), CashView.of(report.cash()),
                report.handoverNote(), printJobId);
    }

    /** The takings matrix, plus the loyalty and count lines that print beside it. */
    @Schema(name = "ShiftTakings")
    public record TakingsView(List<MethodView> byMethod,
                              MethodView totals,
                              int pointsRedeemed,
                              int pointsEarned,
                              int saleCount,
                              int refundCount) {

        static TakingsView of(ShiftTakings takings) {
            return new TakingsView(takings.byMethod().stream().map(MethodView::of).toList(),
                    MethodView.of(takings.totals()), takings.pointsRedeemed(),
                    takings.pointsEarned(), takings.saleCount(), takings.refundCount());
        }
    }

    /**
     * One method's takings, split by category. Every method gets a row even at zero, so the FE can
     * render a stable table; {@code booking} is the pre-booking line and {@code tournament} the
     * entry-fee line, both zero until B12/B15 post to them.
     */
    @Schema(name = "ShiftTakingsRow")
    public record MethodView(String method, int gaming, int fnb, int tournament, int booking,
                             int total) {

        static MethodView of(MethodTakings row) {
            return new MethodView(row.method(), row.gaming(), row.fnb(), row.tournament(),
                    row.booking(), row.total());
        }
    }

    /** The petty-cash block: what came out of the drawer, by category and line by line. */
    @Schema(name = "ShiftExpenses")
    public record ExpensesView(int total,
                               int count,
                               List<CategoryView> byCategory,
                               List<ExpenseLineView> lines) {

        static ExpensesView of(ExpenseSummary expenses) {
            return new ExpensesView(expenses.total(), expenses.count(),
                    expenses.byCategory().stream()
                            .map(category -> new CategoryView(category.category(), category.amount()))
                            .toList(),
                    expenses.lines().stream()
                            .map(line -> new ExpenseLineView(line.id(), line.description(),
                                    line.category(), line.amount(), line.at()))
                            .toList());
        }
    }

    @Schema(name = "ShiftExpenseCategoryTotal")
    public record CategoryView(String category, int amount) {
    }

    @Schema(name = "ShiftExpenseLine")
    public record ExpenseLineView(long id, String description, String category, int amount,
                                  OffsetDateTime at) {
    }

    /**
     * The drawer. {@code takings} is cash only — bKash, Nagad and wallet never reach the till.
     *
     * @param counted     absent until someone counts it at close
     * @param discrepancy {@code counted - expected}: positive over, negative short
     */
    @Schema(name = "ShiftCash")
    public record CashView(int openingFloat, int takings, int expenses, int expected,
                           Integer counted, Integer discrepancy) {

        static CashView of(CashCount cash) {
            return new CashView(cash.openingFloat(), cash.takings(), cash.expenses(),
                    cash.expected(), cash.counted(), cash.discrepancy());
        }
    }
}
