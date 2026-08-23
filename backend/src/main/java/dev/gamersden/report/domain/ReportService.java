package dev.gamersden.report.domain;

import dev.gamersden.common.config.VenueTime;
import dev.gamersden.common.spi.BookingStatsLookup;
import dev.gamersden.common.spi.BookingStatsLookup.BookingIncome;
import dev.gamersden.common.spi.BookingStatsLookup.DailyBookings;
import dev.gamersden.common.spi.ExpenseLookup;
import dev.gamersden.common.spi.OccupancyLookup;
import dev.gamersden.common.spi.OccupancyLookup.SessionSpan;
import dev.gamersden.common.spi.RevenueLookup;
import dev.gamersden.common.spi.RevenueLookup.HourlyRevenue;
import dev.gamersden.common.spi.SalesItemLookup;
import dev.gamersden.common.spi.SalesItemLookup.ItemSales;
import dev.gamersden.common.spi.ShiftHistoryLookup;
import dev.gamersden.common.spi.StationLookup;
import dev.gamersden.common.spi.StationLookup.StationInfo;
import dev.gamersden.report.domain.RangeReport.BookingReport;
import dev.gamersden.report.domain.RangeReport.HourSlice;
import dev.gamersden.report.domain.RangeReport.StationUse;
import dev.gamersden.report.domain.TimeSpans.Span;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * S9's aggregates (design.md S9, docs/bookings.md §6; TASKLIST B20).
 *
 * <p>This class owns no tables and reads none. Every figure comes through one of the {@code common}
 * SPI doors, each implemented by the package that owns the rows (ARCHITECTURE.md §3) — money from
 * {@code billing}, petty cash and trading hours from {@code shift}, occupancy from
 * {@code session}, sold items from {@code catalog}, slots from {@code booking}, seats from
 * {@code station}. What is left here is the folding, and the folding is deliberate about three
 * things:
 *
 * <ul>
 *   <li><b>Nothing is stored.</b> The whole report is derived per request (invariant §5.4). There
 *       is no rollup table to fall behind a void or a refund.</li>
 *   <li><b>Time is measured against trading hours, not the wall clock.</b> A console idle at 4am
 *       is shut, not under-used, so utilisation is a share of the hours a till was open.</li>
 *   <li><b>The future is not counted.</b> A window that runs to the end of today is clipped at
 *       now, or every report of today would show the evening as empty consoles.</li>
 * </ul>
 */
@Service
public class ReportService {

    /** design.md S9's top-seller table is five rows deep. */
    private static final int TOP_SELLERS = 5;

    private static final int HOURS_OF_DAY = 24;

    private final RevenueLookup revenue;
    private final ExpenseLookup expenses;
    private final ShiftHistoryLookup shifts;
    private final OccupancyLookup occupancy;
    private final SalesItemLookup catalogue;
    private final BookingStatsLookup bookings;
    private final StationLookup stations;
    private final Clock clock;

    public ReportService(RevenueLookup revenue,
                         ExpenseLookup expenses,
                         ShiftHistoryLookup shifts,
                         OccupancyLookup occupancy,
                         SalesItemLookup catalogue,
                         BookingStatsLookup bookings,
                         StationLookup stations,
                         Clock clock) {
        this.revenue = revenue;
        this.expenses = expenses;
        this.shifts = shifts;
        this.occupancy = occupancy;
        this.catalogue = catalogue;
        this.bookings = bookings;
        this.stations = stations;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public RangeReport report(ReportWindow window) {
        OffsetDateTime now = VenueTime.now(clock);
        OffsetDateTime from = window.startsAt();
        OffsetDateTime to = window.endsAt();
        OffsetDateTime elapsedTo = window.elapsedEnd(now);

        List<DayMoney> trend = MoneySeries.of(window, revenue.dailyRevenue(from, to),
                expenses.dailyExpenses(from, to));
        MoneyTotals totals = MoneyTotals.sum(trend.stream().map(DayMoney::money).toList());

        List<SessionSpan> seats = occupancy.sessionSpans(from, to);
        long tradingSeconds = tradingSeconds(from, elapsedTo);

        return new RangeReport(window, now, totals, startedIn(seats, from, to), trend,
                tradingSeconds, utilisation(seats, from, elapsedTo, tradingSeconds),
                busiestHours(seats, from, to, elapsedTo), topSellers(from, to),
                bookingReport(from, to, now));
    }

    // ---- time -----------------------------------------------------------------------------

    /**
     * The hours the venue was open, counted once however many terminals were trading at the time —
     * the denominator every utilisation figure is a share of.
     */
    private long tradingSeconds(OffsetDateTime from, OffsetDateTime elapsedTo) {
        List<Span> tills = shifts.tradingSpans(from, elapsedTo).stream()
                .map(span -> new Span(span.openedAt(), span.closedAt()))
                .toList();
        return TimeSpans.unionSeconds(TimeSpans.clipAll(tills, from, elapsedTo));
    }

    private static int startedIn(List<SessionSpan> seats, OffsetDateTime from, OffsetDateTime to) {
        return (int) seats.stream()
                .filter(seat -> !seat.startedAt().isBefore(from) && seat.startedAt().isBefore(to))
                .count();
    }

    /**
     * One bar per station, including seats that earned nothing — a console that sat idle all week
     * is exactly what a utilisation chart is for, and leaving it out would hide it.
     */
    private List<StationUse> utilisation(List<SessionSpan> seats,
                                         OffsetDateTime from,
                                         OffsetDateTime elapsedTo,
                                         long tradingSeconds) {
        Map<Long, List<SessionSpan>> byStation = seats.stream()
                .collect(Collectors.groupingBy(SessionSpan::stationId));
        return stations.all().stream()
                .map(station -> use(station, byStation.getOrDefault(station.id(), List.of()),
                        from, elapsedTo, tradingSeconds))
                .toList();
    }

    private static StationUse use(StationInfo station,
                                  List<SessionSpan> seats,
                                  OffsetDateTime from,
                                  OffsetDateTime elapsedTo,
                                  long tradingSeconds) {
        long busy = TimeSpans.clipAll(spans(seats), from, elapsedTo).stream()
                .mapToLong(Span::seconds)
                .sum();
        double pct = tradingSeconds == 0 ? 0 : oneDecimal(100.0 * busy / tradingSeconds);
        return new StationUse(station.id(), station.name(), station.consoleType(),
                station.underMaintenance(), startedIn(seats, from, elapsedTo), busy, pct);
    }

    /**
     * The 24 venue hours, every one of them present so the table never shifts rows about.
     *
     * <p>{@code avgStationsBusy} divides occupied seat-seconds in an hour by how much of that hour
     * the window has actually contained — one 18:00 in a one-day window, fourteen in a fortnight,
     * and only the elapsed part of today. Dividing by the window's whole length instead would
     * report a busy evening as quiet whenever the report was pulled at lunchtime.
     */
    private List<HourSlice> busiestHours(List<SessionSpan> seats,
                                         OffsetDateTime from,
                                         OffsetDateTime to,
                                         OffsetDateTime elapsedTo) {
        long[] busy = TimeSpans.byHourOfDay(TimeSpans.clipAll(spans(seats), from, elapsedTo));
        long[] elapsed = elapsedTo.isAfter(from)
                ? TimeSpans.byHourOfDay(List.of(new Span(from, elapsedTo)))
                : new long[HOURS_OF_DAY];
        Map<Integer, HourlyRevenue> money = revenue.hourlyRevenue(from, to).stream()
                .collect(Collectors.toMap(HourlyRevenue::hour, Function.identity()));
        return IntStream.range(0, HOURS_OF_DAY)
                .mapToObj(hour -> {
                    HourlyRevenue taken = money.get(hour);
                    double average = elapsed[hour] == 0 ? 0
                            : oneDecimal((double) busy[hour] / elapsed[hour]);
                    return new HourSlice(hour, taken == null ? 0 : taken.revenue(),
                            taken == null ? 0 : taken.sales(), busy[hour], average);
                })
                .toList();
    }

    private static List<Span> spans(List<SessionSpan> seats) {
        return seats.stream().map(seat -> new Span(seat.startedAt(), seat.endedAt())).toList();
    }

    // ---- the rest -------------------------------------------------------------------------

    /**
     * Two packages, one table. {@code billing} decides which carts a sale that stuck settled in
     * the window; {@code catalog} prices their lines out. Neither reads the other's rows.
     */
    private List<ItemSales> topSellers(OffsetDateTime from, OffsetDateTime to) {
        return catalogue.topSellers(revenue.settledCartIds(from, to), TOP_SELLERS);
    }

    private BookingReport bookingReport(OffsetDateTime from, OffsetDateTime to,
                                        OffsetDateTime now) {
        List<DailyBookings> perDay = bookings.dailyBookings(from, to, now);
        int booked = perDay.stream().mapToInt(DailyBookings::booked).sum();
        int used = perDay.stream().mapToInt(DailyBookings::used).sum();
        int cancelled = perDay.stream().mapToInt(DailyBookings::cancelled).sum();
        int arrived = perDay.stream().mapToInt(DailyBookings::arrived).sum();
        int expired = perDay.stream().mapToInt(DailyBookings::expired).sum();
        BookingIncome income = bookings.income(from, to);
        return new BookingReport(perDay, booked, used, cancelled, arrived, expired,
                showRate(used, cancelled, expired), income.bookings(), income.playAmount(),
                income.packageFee());
    }

    /**
     * {@code USED / (USED + CANCELLED + expired)} (docs/bookings.md §6), as a percentage — and
     * null rather than zero when nothing has resolved, because a rate over no bookings is unknown,
     * not perfect and not terrible.
     */
    private static Double showRate(int used, int cancelled, int expired) {
        int resolved = used + cancelled + expired;
        return resolved == 0 ? null : oneDecimal(100.0 * used / resolved);
    }

    private static double oneDecimal(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
