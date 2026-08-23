package dev.gamersden.report.domain;

import dev.gamersden.common.config.VenueTime;
import dev.gamersden.common.spi.BookingStatsLookup;
import dev.gamersden.common.spi.BookingStatsLookup.PreSoldBookings;
import dev.gamersden.common.spi.ExpenseLookup;
import dev.gamersden.common.spi.OccupancyLookup;
import dev.gamersden.common.spi.QueuePreSoldLookup;
import dev.gamersden.common.spi.QueuePreSoldLookup.PreSoldTokens;
import dev.gamersden.common.spi.RevenueLookup;
import dev.gamersden.common.spi.SalesItemLookup;
import dev.gamersden.common.spi.SessionLookup;
import dev.gamersden.common.spi.ShiftHistoryLookup;
import dev.gamersden.common.spi.ShiftHistoryLookup.ClosedShift;
import dev.gamersden.common.spi.StationLookup;
import dev.gamersden.common.spi.StationLookup.StationInfo;
import dev.gamersden.report.domain.Overview.Occupancy;
import dev.gamersden.report.domain.Overview.PreSold;
import dev.gamersden.report.domain.Overview.ShiftClose;
import dev.gamersden.report.domain.Overview.WeekdayMoney;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * S2's aggregates (design.md S2, docs/bookings.md §6; TASKLIST B20).
 *
 * <p>Same rules as {@link ReportService}: no tables of its own, every figure through a
 * {@code common} SPI door, nothing stored. Two things are specific to this screen.
 *
 * <p><b>The 30-day trend is read as 60.</b> S2 prints "+11% on the previous 30" under the chart,
 * which needs the window before this one as well. One grouped query over both and a split in
 * memory beats two round trips for the same rows.
 *
 * <p><b>Occupancy is now, not today.</b> The tile says how many seats are full at this instant, so
 * it comes from the live-session read the Floor uses, not from the day's history.
 */
@Service
public class OverviewService {

    /** design.md S2's watchlist is about seven rows tall. */
    private static final int WATCHLIST = 8;

    /** The rail under it lists the last handful of closes. */
    private static final int RECENT_CLOSES = 5;

    private final RevenueLookup revenue;
    private final ExpenseLookup expenses;
    private final ShiftHistoryLookup shifts;
    private final OccupancyLookup occupancy;
    private final SessionLookup sessions;
    private final SalesItemLookup catalogue;
    private final BookingStatsLookup bookings;
    private final QueuePreSoldLookup tokens;
    private final StationLookup stations;
    private final Clock clock;

    public OverviewService(RevenueLookup revenue,
                           ExpenseLookup expenses,
                           ShiftHistoryLookup shifts,
                           OccupancyLookup occupancy,
                           SessionLookup sessions,
                           SalesItemLookup catalogue,
                           BookingStatsLookup bookings,
                           QueuePreSoldLookup tokens,
                           StationLookup stations,
                           Clock clock) {
        this.revenue = revenue;
        this.expenses = expenses;
        this.shifts = shifts;
        this.occupancy = occupancy;
        this.sessions = sessions;
        this.catalogue = catalogue;
        this.bookings = bookings;
        this.tokens = tokens;
        this.stations = stations;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Overview overview() {
        OffsetDateTime now = VenueTime.now(clock);
        LocalDate today = VenueTime.businessDay(clock);
        ReportWindow window = ReportWindow.endingToday(clock, ReportWindow.OVERVIEW_DAYS);
        ReportWindow both = new ReportWindow(window.previous().from(), window.to());

        List<DayMoney> series = MoneySeries.of(both,
                revenue.dailyRevenue(both.startsAt(), both.endsAt()),
                expenses.dailyExpenses(both.startsAt(), both.endsAt()));
        List<DayMoney> last30 = series.stream()
                .filter(day -> !day.day().isBefore(window.from()))
                .toList();
        List<DayMoney> previous30 = series.stream()
                .filter(day -> day.day().isBefore(window.from()))
                .toList();

        MoneyTotals todayTotals = last30.stream()
                .filter(day -> day.day().equals(today))
                .map(DayMoney::money)
                .findFirst()
                .orElse(MoneyTotals.ZERO);

        return new Overview(now, today, occupancyNow(), todayTotals, sessionsToday(today),
                preSold(), last30, revenueOf(last30), revenueOf(previous30),
                byDayOfWeek(last30), catalogue.stockWatchlist(WATCHLIST), recentCloses());
    }

    /**
     * "2 of 4 busy". The denominator leaves out seats under maintenance: a console in pieces is not
     * an empty console, and counting it as one would flatter every quiet evening.
     */
    private Occupancy occupancyNow() {
        List<StationInfo> floor = stations.all();
        int maintenance = (int) floor.stream().filter(StationInfo::underMaintenance).count();
        int available = floor.size() - maintenance;
        int busy = sessions.liveSessionsByStation().size();
        double pct = available == 0 ? 0 : Math.round(1000.0 * busy / available) / 10.0;
        return new Occupancy(busy, floor.size(), maintenance, pct);
    }

    /** Seats opened since venue midnight — the "38 sessions today" line under the average ticket. */
    private int sessionsToday(LocalDate today) {
        ReportWindow day = new ReportWindow(today, today);
        OffsetDateTime from = day.startsAt();
        OffsetDateTime to = day.endsAt();
        return (int) occupancy.sessionSpans(from, to).stream()
                .filter(seat -> !seat.startedAt().isBefore(from) && seat.startedAt().isBefore(to))
                .count();
    }

    /** Money taken for play not yet delivered (docs/bookings.md §6). */
    private PreSold preSold() {
        PreSoldBookings booked = bookings.preSold();
        PreSoldTokens waiting = tokens.waitingPlayTickets();
        return new PreSold(booked.bookings(), booked.playAmount(), booked.packageFee(),
                waiting.tokens(), waiting.amount());
    }

    private static int revenueOf(List<DayMoney> series) {
        return series.stream().mapToInt(day -> day.money().revenue()).sum();
    }

    /**
     * The seven weekday bars. The average divides by how many times that weekday actually fell in
     * the window — 30 days is four of some weekdays and five of others, and dividing them all by
     * the same number would make Monday look worse than Tuesday for owning a calendar.
     */
    private static List<WeekdayMoney> byDayOfWeek(List<DayMoney> series) {
        Map<DayOfWeek, int[]> folded = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek day : DayOfWeek.values()) {
            folded.put(day, new int[] {0, 0});
        }
        for (DayMoney day : series) {
            int[] cell = folded.get(day.day().getDayOfWeek());
            cell[0] += day.money().revenue();
            cell[1]++;
        }
        return List.of(DayOfWeek.values()).stream()
                .map(day -> {
                    int[] cell = folded.get(day);
                    int average = cell[1] == 0 ? 0 : (int) Math.round((double) cell[0] / cell[1]);
                    return new WeekdayMoney(day, cell[0], cell[1], average);
                })
                .toList();
    }

    private List<ShiftClose> recentCloses() {
        List<ClosedShift> closed = shifts.recentCloses(RECENT_CLOSES);
        Map<Long, Integer> takings = revenue.takingsByShift(
                closed.stream().map(ClosedShift::shiftId).toList());
        return closed.stream()
                .map(shift -> new ShiftClose(shift.shiftId(), shift.staffId(), shift.terminal(),
                        shift.openedAt(), shift.closedAt(), shift.openingFloat(),
                        takings.getOrDefault(shift.shiftId(), 0), shift.countedCash(),
                        shift.expectedCash(), shift.discrepancy()))
                .toList();
    }
}
