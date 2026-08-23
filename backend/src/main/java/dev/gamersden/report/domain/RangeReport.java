package dev.gamersden.report.domain;

import dev.gamersden.common.spi.BookingStatsLookup.DailyBookings;
import dev.gamersden.common.spi.SalesItemLookup.ItemSales;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * S9 (design.md §1) as one read: KPIs, the stacked revenue trend, per-station utilisation,
 * busiest hours, top sellers, and the three booking figures docs/bookings.md §6 asks reports for.
 *
 * <p>Everything is derived at request time from grouped reads — no rollup table, no cached
 * numbers (TASKLIST B20, invariant §5.4). Ask again after the next sale and the figures move.
 *
 * @param asOf server time this was computed at; the FE never derives it from the local clock (§5.1)
 * @param sessions seats opened inside the window — the "38 sessions today" line under the KPIs
 */
public record RangeReport(ReportWindow window,
                          OffsetDateTime asOf,
                          MoneyTotals totals,
                          int sessions,
                          List<DayMoney> trend,
                          long tradingSeconds,
                          List<StationUse> stationUtilisation,
                          List<HourSlice> busiestHours,
                          List<ItemSales> topSellers,
                          BookingReport bookings) {

    /**
     * One station's share of the trading hours.
     *
     * <p>{@code utilisationPct} is measured against the hours the venue actually had a till open,
     * not against the wall clock: a console idle at 4am is shut, not under-used. When nobody
     * traded in the window at all there is nothing to be a percentage of, and it reads 0 — the
     * companion {@code tradingSeconds} on the report is what tells S9 to show "not enough data
     * yet" rather than a row of empty bars.
     *
     * @param busySeconds wall-clock time the seat was occupied, paused clocks included: a paused
     *                    seat is still a seat nobody else can take
     */
    public record StationUse(long stationId,
                             String name,
                             String consoleType,
                             boolean underMaintenance,
                             int sessions,
                             long busySeconds,
                             double utilisationPct) {
    }

    /**
     * One venue hour-of-day, averaged over the days of the window — S9's busiest-hours table.
     *
     * @param avgStationsBusy stations occupied on average during this hour ("2.4 / 4"), which is
     *                        occupied seat-seconds over the hours the window contains
     */
    public record HourSlice(int hour,
                            int revenue,
                            int sales,
                            long busySeconds,
                            double avgStationsBusy) {
    }

    /**
     * The booking block (docs/bookings.md §6): slots per day, the show-rate, and what pre-booking
     * brought in split into play time and package fee.
     *
     * <p>The two halves are keyed on different dates on purpose. Attendance counts slots by the
     * day they were <em>due</em> ({@code start_at}); income counts money by the day it was
     * <em>taken</em> ({@code created_at}). A booking sold on Monday for Tuesday is in each once,
     * on different days.
     *
     * @param showRatePct {@code used / (used + cancelled + expired)}, or null when nothing in the
     *                    window has resolved yet — a rate over no bookings is not zero, it is
     *                    unknown, and S9 renders that as "not enough data yet"
     * @param expired     still PAID with the slot already past: paid for, never turned up
     * @param arrived     checked in but not yet seated — in neither half of the show-rate, because
     *                    they did turn up and the visit has not finished either way
     */
    public record BookingReport(List<DailyBookings> perDay,
                                int booked,
                                int used,
                                int cancelled,
                                int arrived,
                                int expired,
                                Double showRatePct,
                                int sold,
                                int playIncome,
                                int packageFeeIncome) {

        /** What pre-booking took in the window — the same money as the X/Z pre-booking line. */
        public int income() {
            return playIncome + packageFeeIncome;
        }
    }
}
