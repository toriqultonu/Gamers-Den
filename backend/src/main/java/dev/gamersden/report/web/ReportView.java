package dev.gamersden.report.web;

import dev.gamersden.common.spi.BookingStatsLookup.DailyBookings;
import dev.gamersden.common.spi.SalesItemLookup.ItemSales;
import dev.gamersden.report.domain.RangeReport;
import dev.gamersden.report.domain.RangeReport.BookingReport;
import dev.gamersden.report.domain.RangeReport.HourSlice;
import dev.gamersden.report.domain.RangeReport.StationUse;
import dev.gamersden.report.domain.ReportWindow;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@code GET /reports} — S9 in one shape (design.md S9, docs/bookings.md §6).
 *
 * <p>Nothing here is stored: every figure is folded from grouped reads when the request arrives
 * (invariant §5.4), so a void or a refund is reflected the moment it is written and there is no
 * rollup to fall behind.
 *
 * <p>Two fields tell S9 when to draw "not enough data yet" rather than an empty chart:
 * {@code tradingSeconds} is 0 when no till was open in the range (so every utilisation bar is a
 * share of nothing), and {@code bookings.showRatePct} is absent when no booking in the range has
 * resolved either way.
 *
 * @param serverTime when this was computed; the FE never derives it from the local clock (§5.1)
 * @param tradingSeconds how long the venue had a till open in the range, counting overlapping
 *                       terminals once — the denominator of every {@code utilisationPct}
 */
@Schema(name = "Report", description = "S9's aggregates over a range of venue days")
public record ReportView(RangeView range,
                         OffsetDateTime serverTime,
                         MoneyView kpis,
                         List<DayMoneyView> trend,
                         long tradingSeconds,
                         List<StationUseView> stationUtilisation,
                         List<HourView> busiestHours,
                         List<TopSellerView> topSellers,
                         BookingsView bookings) {

    public static ReportView of(RangeReport report) {
        return new ReportView(RangeView.of(report.window()), report.asOf(),
                MoneyView.of(report.totals(), report.sessions()),
                report.trend().stream().map(DayMoneyView::of).toList(),
                report.tradingSeconds(),
                report.stationUtilisation().stream().map(StationUseView::of).toList(),
                report.busiestHours().stream().map(HourView::of).toList(),
                report.topSellers().stream().map(TopSellerView::of).toList(),
                BookingsView.of(report.bookings()));
    }

    /** The venue days asked for, both ends inclusive. */
    @Schema(name = "ReportRange")
    public record RangeView(LocalDate from, LocalDate to, int days) {

        static RangeView of(ReportWindow window) {
            return new RangeView(window.from(), window.to(), window.days());
        }
    }

    /**
     * One station's utilisation bar. Every seat gets a row, maintenance and idle ones included —
     * a console that earned nothing all week is exactly what the chart is for.
     *
     * @param busySeconds wall-clock occupancy, paused clocks included: a paused seat is still a
     *                    seat nobody else can take
     * @param utilisationPct {@code busySeconds} as a share of {@code tradingSeconds}, not of the
     *                       wall clock — a console idle at 4am is shut, not under-used
     */
    @Schema(name = "ReportStationUtilisation")
    public record StationUseView(long stationId,
                                 String name,
                                 String consoleType,
                                 boolean underMaintenance,
                                 int sessions,
                                 long busySeconds,
                                 double utilisationPct) {

        static StationUseView of(StationUse use) {
            return new StationUseView(use.stationId(), use.name(), use.consoleType(),
                    use.underMaintenance(), use.sessions(), use.busySeconds(),
                    use.utilisationPct());
        }
    }

    /**
     * One venue hour-of-day, all 24 always present.
     *
     * @param avgStationsBusy seats occupied on average during this hour across the range ("2.4"
     *                        against the station count) — occupied seat-seconds over the amount of
     *                        that hour the range has actually contained, so pulling a report at
     *                        lunchtime does not report the evening as quiet
     */
    @Schema(name = "ReportHour")
    public record HourView(int hour,
                           int revenue,
                           int sales,
                           long busySeconds,
                           double avgStationsBusy) {

        static HourView of(HourSlice slice) {
            return new HourView(slice.hour(), slice.revenue(), slice.sales(), slice.busySeconds(),
                    slice.avgStationsBusy());
        }
    }

    /** @param revenue units x the price snapshot each line was sold at, never today's price */
    @Schema(name = "ReportTopSeller")
    public record TopSellerView(long itemId, String name, String category, int units, int revenue) {

        static TopSellerView of(ItemSales sales) {
            return new TopSellerView(sales.itemId(), sales.name(), sales.category(), sales.units(),
                    sales.revenue());
        }
    }

    /**
     * Pre-booking's three report figures (docs/bookings.md §6).
     *
     * <p>{@code perDay} and the four counters are keyed on the slot's {@code startsAt} — this is
     * attendance, and a booking sold on Monday for Tuesday was a Tuesday slot. The income figures
     * are keyed on the day the money was taken instead, so they line up with the pre-booking line
     * on that day's X/Z.
     *
     * @param showRatePct {@code used / (used + cancelled + expired)}; absent when nothing in the
     *                    range has resolved, because a rate over no bookings is unknown rather
     *                    than zero
     * @param expired     paid for, slot already past, never checked in
     * @param arrived     checked in but not yet seated — in neither half of the show-rate
     */
    @Schema(name = "ReportBookings")
    public record BookingsView(List<DayBookingsView> perDay,
                               int booked,
                               int used,
                               int cancelled,
                               int arrived,
                               int expired,
                               Double showRatePct,
                               int sold,
                               int playIncome,
                               int packageFeeIncome,
                               int income) {

        static BookingsView of(BookingReport report) {
            return new BookingsView(report.perDay().stream().map(DayBookingsView::of).toList(),
                    report.booked(), report.used(), report.cancelled(), report.arrived(),
                    report.expired(), report.showRatePct(), report.sold(), report.playIncome(),
                    report.packageFeeIncome(), report.income());
        }
    }

    /** @param date the venue day the slots were due to start on */
    @Schema(name = "ReportBookingsDay")
    public record DayBookingsView(LocalDate date,
                                  int booked,
                                  int used,
                                  int cancelled,
                                  int arrived,
                                  int expired) {

        static DayBookingsView of(DailyBookings day) {
            return new DayBookingsView(day.day(), day.booked(), day.used(), day.cancelled(),
                    day.arrived(), day.expired());
        }
    }
}
