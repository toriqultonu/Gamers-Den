package dev.gamersden.report.domain;

import java.util.Collection;

/**
 * One slice of the money line — a day, a window, or "today" — folded the way both S2 and S9 show
 * it (design.md S2/S9).
 *
 * <p>Two different figures live here and it matters which is which. The four buckets are the
 * transaction's own gross snapshot of what was sold, so they add up to
 * {@code revenue + pointsRedeemed}. {@code revenue} is what was actually tendered, and it is the
 * one net profit is measured from — a bill paid half in loyalty points put less in the till than
 * it did on the menu.
 *
 * <p>Nothing here is stored (invariant §5.4). Every instance is folded from a grouped read at the
 * moment it is asked for.
 *
 * @param transactions rows posted in the slice, refunds and void reversals included
 * @param sales        the subset that took money in — the average ticket's denominator, so a
 *                     refund lowers the average through {@code revenue} once instead of twice
 */
public record MoneyTotals(int gaming,
                          int fnb,
                          int tournament,
                          int booking,
                          int pointsRedeemed,
                          int revenue,
                          int expenses,
                          int transactions,
                          int sales) {

    public static final MoneyTotals ZERO = new MoneyTotals(0, 0, 0, 0, 0, 0, 0, 0, 0);

    /** Takings less petty cash — "net profit" everywhere the two screens use the phrase. */
    public int netProfit() {
        return revenue - expenses;
    }

    /** What the four buckets came to before the points discount. */
    public int gross() {
        return gaming + fnb + tournament + booking;
    }

    /** Revenue per sale, to the nearest taka; 0 when nothing sold, because no ticket has no average. */
    public int avgTicket() {
        return sales == 0 ? 0 : (int) Math.round((double) revenue / sales);
    }

    public MoneyTotals plus(MoneyTotals other) {
        return new MoneyTotals(gaming + other.gaming, fnb + other.fnb,
                tournament + other.tournament, booking + other.booking,
                pointsRedeemed + other.pointsRedeemed, revenue + other.revenue,
                expenses + other.expenses, transactions + other.transactions,
                sales + other.sales);
    }

    public static MoneyTotals sum(Collection<MoneyTotals> slices) {
        return slices.stream().reduce(ZERO, MoneyTotals::plus);
    }

    /** The same slice with its petty cash filled in — expenses arrive from a second query. */
    public MoneyTotals withExpenses(int spent) {
        return new MoneyTotals(gaming, fnb, tournament, booking, pointsRedeemed, revenue, spent,
                transactions, sales);
    }
}
