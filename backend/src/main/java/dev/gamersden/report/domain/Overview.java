package dev.gamersden.report.domain;

import dev.gamersden.common.spi.SalesItemLookup.StockWatch;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * S2 (design.md §1) as one read: the four KPI tiles, the pre-sold stat, the 30-day and
 * day-of-week trends, the stock watchlist and the recent shift closes.
 *
 * <p>The live station cards S2 scrolls through are not here — they are the Floor's own read
 * ({@code GET /stations}), and duplicating them would give the same screen two sources of truth
 * for one card.
 *
 * @param asOf server time this was computed at (§5.1)
 * @param today the venue day the KPI tiles cover — "today" is Dhaka's, not UTC's
 */
public record Overview(OffsetDateTime asOf,
                       LocalDate today,
                       Occupancy occupancy,
                       MoneyTotals todayTotals,
                       int todaySessions,
                       PreSold preSold,
                       List<DayMoney> last30Days,
                       int last30DaysRevenue,
                       int previous30DaysRevenue,
                       List<WeekdayMoney> byDayOfWeek,
                       List<StockWatch> stockWatchlist,
                       List<ShiftClose> recentCloses) {

    /**
     * The occupancy tile, right now.
     *
     * @param stations   every seat on the floor
     * @param maintenance the subset an Admin has taken off it
     * @param pct        busy over the seats that could be busy — a console in pieces is not an
     *                   empty console, and counting it as one would flatter every quiet evening
     */
    public record Occupancy(int busy, int stations, int maintenance, double pct) {

        public int available() {
            return stations - maintenance;
        }
    }

    /**
     * Money taken for play that has not been delivered yet (docs/bookings.md §6): bookings still
     * PAID, plus play tickets still WAITING.
     *
     * <p>A booking that has been checked in is in neither figure. It has left the PAID column, and
     * the token it was given at check-in is a {@code BOOKING}-source entry rather than a play
     * ticket — counting it on the queue side would bill the same money to the stat twice.
     */
    public record PreSold(int bookings,
                          int bookingPlayAmount,
                          int bookingPackageFee,
                          int playTickets,
                          int playTicketAmount) {

        public int bookingAmount() {
            return bookingPlayAmount + bookingPackageFee;
        }

        public int amount() {
            return bookingAmount() + playTicketAmount;
        }
    }

    /**
     * One weekday across the 30-day window — S2's "by day of week" bars.
     *
     * @param days how many times that weekday fell in the window; the divisor behind {@code average}
     */
    public record WeekdayMoney(DayOfWeek day, int revenue, int days, int average) {
    }

    /**
     * One closed till in S2's rail.
     *
     * @param takings everything posted to the shift, all methods — the figure beside the close;
     *                {@code discrepancy} is the cash-only count that ran alongside it
     */
    public record ShiftClose(long shiftId,
                             long staffId,
                             String terminal,
                             OffsetDateTime openedAt,
                             OffsetDateTime closedAt,
                             int openingFloat,
                             int takings,
                             Integer countedCash,
                             Integer expectedCash,
                             Integer discrepancy) {
    }
}
