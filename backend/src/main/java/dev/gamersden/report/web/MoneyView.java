package dev.gamersden.report.web;

import dev.gamersden.report.domain.MoneyTotals;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The KPI row both report screens open with — S2's four tiles and S9's four (design.md S2/S9).
 *
 * <p>Two guarantees the screens are built on: {@code gaming + fnb + tournament + booking ==
 * revenue + pointsRedeemed} (the buckets are gross, revenue is what was tendered), and
 * {@code netProfit == revenue - expenses}. The tournament and pre-booking buckets are the same
 * splits the X/Z prints, read with the same columns (invariant §5.7), so a report and a Z of the
 * same hours cannot disagree.
 *
 * @param sales    transactions that took money in; a refund is a transaction but not a sale, which
 *                 is why it lowers {@code avgTicket} through revenue instead of twice
 * @param sessions seats opened in the window
 */
@Schema(name = "ReportKpis", description = "Takings, petty cash and net profit over a period")
public record MoneyView(int revenue,
                        int gaming,
                        int fnb,
                        int tournament,
                        int booking,
                        int pointsRedeemed,
                        int expenses,
                        int netProfit,
                        int transactions,
                        int sales,
                        int avgTicket,
                        int sessions) {

    public static MoneyView of(MoneyTotals totals, int sessions) {
        return new MoneyView(totals.revenue(), totals.gaming(), totals.fnb(), totals.tournament(),
                totals.booking(), totals.pointsRedeemed(), totals.expenses(), totals.netProfit(),
                totals.transactions(), totals.sales(), totals.avgTicket(), sessions);
    }
}
