package dev.gamersden.report.web;

import dev.gamersden.common.spi.SalesItemLookup.StockWatch;
import dev.gamersden.report.domain.Overview;
import dev.gamersden.report.domain.Overview.Occupancy;
import dev.gamersden.report.domain.Overview.PreSold;
import dev.gamersden.report.domain.Overview.ShiftClose;
import dev.gamersden.report.domain.Overview.WeekdayMoney;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@code GET /overview} — S2 in one shape (design.md S2, docs/bookings.md §6).
 *
 * <p>The live station cards S2 scrolls through are deliberately absent: they are the Floor's read
 * ({@code GET /stations}), and shipping them twice would give one screen two sources of truth for
 * the same card. So is the alerts rail, which is {@code GET /alerts}.
 *
 * @param serverTime when this was computed; countdowns and "as of" labels derive from it, never
 *                   from the browser's clock (§5.1)
 * @param date       the venue day the {@code today} tiles cover — Dhaka's day, not UTC's
 */
@Schema(name = "Overview", description = "S2's KPIs, pre-sold stat, trends, watchlist and closes")
public record OverviewView(OffsetDateTime serverTime,
                           LocalDate date,
                           OccupancyView occupancy,
                           MoneyView today,
                           PreSoldView preSold,
                           TrendView revenue30Days,
                           List<WeekdayView> byDayOfWeek,
                           List<StockWatchView> stockWatchlist,
                           List<ShiftCloseView> recentCloses) {

    public static OverviewView of(Overview overview) {
        return new OverviewView(overview.asOf(), overview.today(),
                OccupancyView.of(overview.occupancy()),
                MoneyView.of(overview.todayTotals(), overview.todaySessions()),
                PreSoldView.of(overview.preSold()),
                new TrendView(overview.last30Days().stream().map(DayMoneyView::of).toList(),
                        overview.last30DaysRevenue(), overview.previous30DaysRevenue()),
                overview.byDayOfWeek().stream().map(WeekdayView::of).toList(),
                overview.stockWatchlist().stream().map(StockWatchView::of).toList(),
                overview.recentCloses().stream().map(ShiftCloseView::of).toList());
    }

    /**
     * Seats full right now.
     *
     * @param available seats that could be busy — {@code stations} less {@code maintenance}, and
     *                  the denominator of {@code pct}: a console in pieces is not an empty console
     */
    @Schema(name = "OverviewOccupancy")
    public record OccupancyView(int busy, int stations, int maintenance, int available, double pct) {

        static OccupancyView of(Occupancy occupancy) {
            return new OccupancyView(occupancy.busy(), occupancy.stations(),
                    occupancy.maintenance(), occupancy.available(), occupancy.pct());
        }
    }

    /**
     * Money taken for play that has not been delivered (docs/bookings.md §6): bookings still PAID
     * plus play tickets still WAITING.
     *
     * <p>A booking that has been checked in is in neither half — it has left PAID, and the token
     * it was issued is a booking token rather than a play ticket, so it is not counted twice.
     *
     * @param amount the whole stat: {@code bookingAmount + playTicketAmount}
     */
    @Schema(name = "OverviewPreSold")
    public record PreSoldView(int bookings,
                              int bookingPlayAmount,
                              int bookingPackageFee,
                              int bookingAmount,
                              int playTickets,
                              int playTicketAmount,
                              int amount) {

        static PreSoldView of(PreSold preSold) {
            return new PreSoldView(preSold.bookings(), preSold.bookingPlayAmount(),
                    preSold.bookingPackageFee(), preSold.bookingAmount(), preSold.playTickets(),
                    preSold.playTicketAmount(), preSold.amount());
        }
    }

    /**
     * The 30-day chart and the line under it.
     *
     * @param previousRevenue the 30 days before those — what "+11% on the previous 30" compares to
     */
    @Schema(name = "OverviewTrend")
    public record TrendView(List<DayMoneyView> days, int revenue, int previousRevenue) {
    }

    /** @param days how many times this weekday fell in the window — the divisor behind {@code average} */
    @Schema(name = "OverviewWeekday")
    public record WeekdayView(String day, int revenue, int days, int average) {

        static WeekdayView of(WeekdayMoney weekday) {
            return new WeekdayView(weekday.day().name(), weekday.revenue(), weekday.days(),
                    weekday.average());
        }
    }

    /** Active items at or below their reorder point, deepest shortfall first. */
    @Schema(name = "OverviewStockWatch")
    public record StockWatchView(long itemId,
                                 String name,
                                 String category,
                                 int stock,
                                 int reorderAt) {

        static StockWatchView of(StockWatch watch) {
            return new StockWatchView(watch.itemId(), watch.name(), watch.category(),
                    watch.stock(), watch.reorderAt());
        }
    }

    /**
     * One closed till.
     *
     * @param takings everything posted to the shift, every method — the figure beside the close.
     *                {@code discrepancy} is the cash-only count that ran next to it, and a
     *                non-zero one already wrote the alert in S2's rail
     */
    @Schema(name = "OverviewShiftClose")
    public record ShiftCloseView(long shiftId,
                                 long staffId,
                                 String terminal,
                                 OffsetDateTime openedAt,
                                 OffsetDateTime closedAt,
                                 int openingFloat,
                                 int takings,
                                 Integer countedCash,
                                 Integer expectedCash,
                                 Integer discrepancy) {

        static ShiftCloseView of(ShiftClose close) {
            return new ShiftCloseView(close.shiftId(), close.staffId(), close.terminal(),
                    close.openedAt(), close.closedAt(), close.openingFloat(), close.takings(),
                    close.countedCash(), close.expectedCash(), close.discrepancy());
        }
    }
}
