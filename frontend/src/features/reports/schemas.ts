/**
 * S2 and S9's shapes, and every reading they take off them.
 *
 * `GET /overview` and `GET /reports` are folded server-side per request
 * (nothing is stored), so the two screens have no arithmetic of their own to
 * do — what lives here is the *reading*: which figure a tile shows, what its
 * sub-line says, and above all **when a chart has nothing to draw**, which is
 * the state design.md §1 gives S9 by name ("Not enough data yet" per chart).
 *
 * All of it is pure, because all of it is assertable without a DOM: the state
 * table is the test, not a snapshot.
 *
 * Percentages arrive on a 0–100 scale with one decimal (`OverviewService`,
 * `ReportService`) — nothing here rescales them.
 */

import type { Schemas } from '@/lib/api';
import { formatAmount, formatBDT } from '@/lib/money';
import { formatDuration, serverNow, venueDate } from '@/lib/time';
import type { BarDatum } from '@/components/ui/bar-chart';

export type Overview = Schemas['Overview'];
export type Report = Schemas['Report'];
export type ReportKpis = Schemas['ReportKpis'];
export type TrendPoint = Schemas['ReportTrendPoint'];
export type Occupancy = Schemas['OverviewOccupancy'];
export type PreSold = Schemas['OverviewPreSold'];
export type StockWatch = Schemas['OverviewStockWatch'];
export type ShiftClose = Schemas['OverviewShiftClose'];
export type Weekday = Schemas['OverviewWeekday'];
export type StationUtilisation = Schemas['ReportStationUtilisation'];
export type ReportHour = Schemas['ReportHour'];
export type TopSeller = Schemas['ReportTopSeller'];
export type ReportBookings = Schemas['ReportBookings'];
export type ReportBookingsDay = Schemas['ReportBookingsDay'];
export type Alert = Schemas['Alert'];

/** The copy design.md §1 gives every chart with nothing behind it. */
export const NOT_ENOUGH_DATA = 'Not enough data yet';

/* ------------------------------------------------------------------- S2 */

/** `50%` — the occupancy tile's figure. */
export function occupancyPct(occupancy: Occupancy | undefined): string {
  return `${formatPct(occupancy?.pct ?? 0)}%`;
}

/**
 * "2 of 4 busy" — and the consoles in pieces, when there are any.
 *
 * The denominator is `available`, not `stations`: a console on the bench is not
 * an empty console (`OverviewView.OccupancyView`), so saying "2 of 4" while one
 * of the four cannot be sat on would read as a quiet evening rather than a
 * repair.
 */
export function occupancyNote(occupancy: Occupancy | undefined): string {
  const busy = occupancy?.busy ?? 0;
  const available = occupancy?.available ?? 0;
  const maintenance = occupancy?.maintenance ?? 0;
  if (available === 0 && maintenance === 0) return 'No consoles registered';
  const head = `${busy} of ${available} seats busy`;
  return maintenance > 0 ? `${head} · ${maintenance} in service` : head;
}

/**
 * The pre-sold stat's sub-line (docs/bookings.md §6): money taken for play that
 * has not been delivered — bookings still PAID plus play tickets still WAITING.
 */
export function preSoldNote(preSold: PreSold | undefined): string {
  const bookings = preSold?.bookings ?? 0;
  const tickets = preSold?.playTickets ?? 0;
  if (bookings === 0 && tickets === 0) return 'Nothing pre-sold right now';
  const parts: string[] = [];
  if (bookings > 0) parts.push(plural(bookings, 'booking'));
  if (tickets > 0) parts.push(plural(tickets, 'play ticket'));
  return `${parts.join(' · ')} not played yet`;
}

/** "after ৳1,180 expenses" — the net-profit tile's sub-line. */
export function netProfitNote(kpis: ReportKpis | undefined): string {
  const expenses = kpis?.expenses ?? 0;
  return expenses > 0 ? `after ${formatBDT(expenses)} expenses` : 'no petty cash today';
}

/** "38 sessions · 24 sales" — the avg-ticket tile's sub-line. */
export function avgTicketNote(kpis: ReportKpis | undefined): string {
  const sessions = kpis?.sessions ?? 0;
  const sales = kpis?.sales ?? 0;
  if (sessions === 0 && sales === 0) return 'No sessions yet today';
  return `${plural(sessions, 'session')} · ${plural(sales, 'sale')}`;
}

/** Revenue by day, newest last — the axis is the array's own order. */
export function trendBars(days: readonly TrendPoint[] | undefined): BarDatum[] {
  return (days ?? []).map((day) => ({
    label: dayLabel(day.date),
    value: day.revenue ?? 0,
  }));
}

/**
 * "৳392,400 total · +11% on the previous 30".
 *
 * With nothing to compare against, it says so rather than printing `+100%` —
 * a first month is not an infinite improvement on no month.
 */
export function trendSummary(
  trend: Schemas['OverviewTrend'] | undefined,
  days = 30,
): string {
  const revenue = trend?.revenue ?? 0;
  const previous = trend?.previousRevenue ?? 0;
  const total = `${formatBDT(revenue)} total`;
  if (previous <= 0) return `${total} · nothing to compare with yet`;
  const delta = Math.round(((revenue - previous) / previous) * 100);
  const sign = delta > 0 ? '+' : delta < 0 ? '−' : '';
  return `${total} · ${sign}${Math.abs(delta)}% on the previous ${days}`;
}

/** The three-letter weekday label the by-day rows use. */
const WEEKDAY_LABELS: Record<string, string> = {
  MONDAY: 'Mon',
  TUESDAY: 'Tue',
  WEDNESDAY: 'Wed',
  THURSDAY: 'Thu',
  FRIDAY: 'Fri',
  SATURDAY: 'Sat',
  SUNDAY: 'Sun',
};

export type WeekdayRow = {
  key: string;
  label: string;
  /** The average takings on this weekday — the bar and the figure. */
  average: number;
  /** How many of this weekday the window held, for the tooltip. */
  days: number;
};

/**
 * By day of week — the **average**, not the total.
 *
 * A 30-day window holds four or five of each weekday, so totals would rank a
 * weekday by how often it happened to fall in the window. The server already
 * carries the divisor (`days`), which is why it is the divisor used here.
 */
export function weekdayRows(weekdays: readonly Weekday[] | undefined): WeekdayRow[] {
  return (weekdays ?? []).map((weekday) => ({
    key: weekday.day ?? '',
    label: WEEKDAY_LABELS[weekday.day ?? ''] ?? (weekday.day ?? '').slice(0, 3),
    average: weekday.average ?? 0,
    days: weekday.days ?? 0,
  }));
}

/** True once at least one weekday has taken money — else the chart is empty. */
export function hasWeekdayData(weekdays: readonly Weekday[] | undefined): boolean {
  return weekdayRows(weekdays).some((row) => row.average > 0);
}

export type StockWatchRow = {
  key: string;
  name: string;
  note: string;
  stock: number;
  reorderAt: number;
};

/** "3 left · reorder at 6" — the watchlist rows, deepest shortfall first. */
export function stockWatchRows(watchlist: readonly StockWatch[] | undefined): StockWatchRow[] {
  return (watchlist ?? []).map((watch) => {
    const stock = watch.stock ?? 0;
    const reorderAt = watch.reorderAt ?? 0;
    return {
      key: String(watch.itemId ?? watch.name ?? ''),
      name: watch.name ?? '—',
      note: `${stock === 0 ? 'None' : formatAmount(stock)} left · reorder at ${formatAmount(reorderAt)}`,
      stock,
      reorderAt,
    };
  });
}

/**
 * A close's cash verdict. `null` for a close with no count on it at all —
 * an X-report shift that was closed without a drawer count reads "—", never
 * "balanced", because we do not know that it was.
 */
export function discrepancyNote(close: ShiftClose | undefined): string | null {
  const discrepancy = close?.discrepancy;
  if (typeof discrepancy !== 'number') return null;
  if (discrepancy === 0) return 'Balanced';
  return discrepancy < 0
    ? `${formatBDT(discrepancy)} short`
    : `${formatBDT(discrepancy, { sign: 'always' })} over`;
}

/* ---------------------------------------------------------------- alerts */

/** The three types the backend raises (`AlertPublisher`, `PrintQueueStore`). */
export const ALERT_KIND_LABELS: Record<string, string> = {
  CASH_DISCREPANCY: 'Cash',
  PRINTER_FAILED: 'Printer',
  LOW_STOCK: 'Stock',
};

export function alertKindLabel(type: string | undefined): string {
  if (!type) return 'Alert';
  return ALERT_KIND_LABELS[type] ?? titleCase(type);
}

/**
 * What the bell's badge counts — `unread=true` is the same question asked of
 * the server, and the badge counts the feed it already holds so a read landing
 * over SSE moves it without a round trip.
 */
export function unreadCount(alerts: readonly Alert[] | undefined): number {
  return (alerts ?? []).filter((alert) => alert.read !== true).length;
}

/** `99+` past two digits — the badge is 18px and must not reflow the rail. */
export function badgeLabel(count: number): string {
  return count > 99 ? '99+' : String(count);
}

/* ------------------------------------------------------------------- S9 */

export type RangeId = '7d' | '14d' | '30d';

export type RangePreset = {
  id: RangeId;
  label: string;
  days: number;
};

/** design.md S9 draws 14 days; the other two are the same chart, wider. */
export const RANGE_PRESETS: readonly RangePreset[] = [
  { id: '7d', label: '7 days', days: 7 },
  { id: '14d', label: '14 days', days: 14 },
  { id: '30d', label: '30 days', days: 30 },
];

export const DEFAULT_RANGE: RangeId = '14d';

export function rangePreset(id: RangeId): RangePreset {
  return RANGE_PRESETS.find((preset) => preset.id === id) ?? RANGE_PRESETS[1];
}

/**
 * The `from`/`to` a preset asks for, as venue days.
 *
 * Both bounds are inclusive, so `14 days` ends today and starts thirteen days
 * back. The instant is the **server** clock (`lib/time.ts`), never the
 * browser's — and the answer still carries the range the server actually used,
 * which is what the screen prints (§5.2).
 */
export function rangeParams(id: RangeId, at: number = serverNow()): { from: string; to: string } {
  const days = rangePreset(id).days;
  return {
    from: venueDate(at - (days - 1) * 86_400_000),
    to: venueDate(at),
  };
}

/** "21 Aug – 3 Sep · 14 days" — the server's own window, not the request's. */
export function rangeNote(range: Schemas['ReportRange'] | undefined): string {
  if (!range?.from || !range?.to) return '';
  return `${dayLabel(range.from)} – ${dayLabel(range.to)} · ${plural(range.days ?? 0, 'day')}`;
}

/**
 * One stacked column of the trend chart.
 *
 * The prototype stacked two segments because tournaments and pre-bookings did
 * not exist when it was drawn. The money model has four gross buckets and
 * guarantees `gaming + fnb + tournament + booking == revenue + pointsRedeemed`
 * (`MoneyView`), so drawing two would leave a tournament night looking like a
 * quiet one. All four are stacked, in the order the X/Z prints them.
 */
export const TREND_SERIES = [
  { key: 'gaming', label: 'Gaming time' },
  { key: 'fnb', label: 'Food & beverage' },
  { key: 'tournament', label: 'Tournaments' },
  { key: 'booking', label: 'Pre-booking' },
] as const;

export type TrendSeriesKey = (typeof TREND_SERIES)[number]['key'];

export type StackedDay = {
  key: string;
  label: string;
  total: number;
  segments: { key: TrendSeriesKey; value: number }[];
};

export function stackedTrend(trend: readonly TrendPoint[] | undefined): StackedDay[] {
  return (trend ?? []).map((day) => {
    const segments = TREND_SERIES.map((series) => ({
      key: series.key,
      value: Math.max(0, day[series.key] ?? 0),
    }));
    return {
      key: day.date ?? '',
      label: dayLabel(day.date),
      total: segments.reduce((sum, segment) => sum + segment.value, 0),
      segments,
    };
  });
}

/** The tallest column — the scale every segment is measured against. */
export function trendPeak(days: readonly StackedDay[]): number {
  return days.reduce((peak, day) => Math.max(peak, day.total), 0);
}

export type UtilisationRow = {
  key: string;
  name: string;
  consoleType: string;
  pct: number;
  note: string;
};

/**
 * Per-station utilisation, busiest first. Every seat gets a row — maintenance
 * and idle ones included: a console that earned nothing all week is exactly
 * what the chart is for (`ReportView.StationUseView`).
 */
export function utilisationRows(
  stations: readonly StationUtilisation[] | undefined,
): UtilisationRow[] {
  return [...(stations ?? [])]
    .sort((a, b) => (b.utilisationPct ?? 0) - (a.utilisationPct ?? 0))
    .map((station) => ({
      key: String(station.stationId ?? station.name ?? ''),
      name: station.name ?? '—',
      consoleType: station.consoleType ?? '',
      pct: station.utilisationPct ?? 0,
      note: station.underMaintenance
        ? `${formatDuration(station.busySeconds ?? 0)} · in service`
        : `${formatDuration(station.busySeconds ?? 0)} · ${plural(station.sessions ?? 0, 'session')}`,
    }));
}

/**
 * Utilisation is a share of the hours a till was open. With no till open in the
 * range every bar is a share of nothing, which is the server's own reason for
 * sending `tradingSeconds` — so the chart says so instead of drawing zeroes.
 */
export function hasUtilisationData(report: Report | undefined): boolean {
  return (report?.tradingSeconds ?? 0) > 0 && (report?.stationUtilisation?.length ?? 0) > 0;
}

export type HourRow = {
  key: string;
  window: string;
  avgStationsBusy: number;
  revenue: number;
};

/**
 * The busiest hours — the ones that actually traded, takings first.
 *
 * The server sends all 24 so nothing has to be inferred from gaps; a venue that
 * opens at 14:00 should not have fourteen empty rows above its evening.
 */
export function busiestHours(
  hours: readonly ReportHour[] | undefined,
  limit = 6,
): HourRow[] {
  return [...(hours ?? [])]
    .filter((hour) => (hour.revenue ?? 0) > 0 || (hour.busySeconds ?? 0) > 0)
    .sort((a, b) => (b.revenue ?? 0) - (a.revenue ?? 0) || (b.busySeconds ?? 0) - (a.busySeconds ?? 0))
    .slice(0, limit)
    .map((hour) => ({
      key: String(hour.hour ?? 0),
      window: hourWindow(hour.hour ?? 0),
      avgStationsBusy: hour.avgStationsBusy ?? 0,
      revenue: hour.revenue ?? 0,
    }));
}

/** `18:00 – 19:00`, wrapping midnight as `23:00 – 00:00`. */
export function hourWindow(hour: number): string {
  const start = ((Math.trunc(hour) % 24) + 24) % 24;
  const end = (start + 1) % 24;
  return `${pad2(start)}:00 – ${pad2(end)}:00`;
}

/** "2.4 / 4" — occupied seats on average, against the seats there are. */
export function stationsBusyLabel(avg: number, stations: number): string {
  return `${avg.toFixed(1)} / ${stations}`;
}

/* ------------------------------------------------------- bookings (S9) */

export type BookingsDayRow = {
  key: string;
  label: string;
  total: number;
};

/**
 * Bookings per day — every slot due to start that day, whatever became of it
 * (docs/bookings.md §6). Keyed on `startsAt`, so a booking sold on Monday for
 * Tuesday is a Tuesday bar.
 */
export function bookingsPerDay(bookings: ReportBookings | undefined): BookingsDayRow[] {
  return (bookings?.perDay ?? []).map((day) => ({
    key: day.date ?? '',
    label: dayLabel(day.date),
    total: bookingsOnDay(day),
  }));
}

export function bookingsOnDay(day: ReportBookingsDay): number {
  return (
    (day.booked ?? 0) +
    (day.used ?? 0) +
    (day.cancelled ?? 0) +
    (day.arrived ?? 0) +
    (day.expired ?? 0)
  );
}

/**
 * The show-rate line. `showRatePct` is absent when nothing in the range has
 * resolved either way — a rate over no bookings is unknown, not zero, so the
 * tile reads the empty copy rather than `0%` (`ReportView.BookingsView`).
 */
export function showRateNote(bookings: ReportBookings | undefined): string {
  const pct = bookings?.showRatePct;
  if (typeof pct !== 'number') return NOT_ENOUGH_DATA;
  const used = bookings?.used ?? 0;
  const resolved = used + (bookings?.cancelled ?? 0) + (bookings?.expired ?? 0);
  return `${formatPct(pct)}% · ${used} of ${resolved} seats taken up`;
}

export function showRateValue(bookings: ReportBookings | undefined): string {
  const pct = bookings?.showRatePct;
  return typeof pct === 'number' ? `${formatPct(pct)}%` : '—';
}

/** True once any booking in the range exists at all. */
export function hasBookingData(bookings: ReportBookings | undefined): boolean {
  if (!bookings) return false;
  return bookingsPerDay(bookings).some((day) => day.total > 0) || (bookings.sold ?? 0) > 0;
}

/* -------------------------------------------------------------- helpers */

/** `3 Sep` — the axis label on every day-keyed chart. */
export function dayLabel(date: string | undefined): string {
  if (!date) return '';
  // A `YYYY-MM-DD` is a venue day, not an instant: reading it at noon UTC keeps
  // it on its own date whatever the terminal's timezone would have made of it.
  const parsed = Date.parse(`${date}T12:00:00Z`);
  if (Number.isNaN(parsed)) return date;
  return dayFormatter.format(new Date(parsed));
}

const dayFormatter = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'UTC',
  day: 'numeric',
  month: 'short',
});

/** One decimal, and no trailing `.0` — `62.5%`, `50%`. */
export function formatPct(pct: number): string {
  if (!Number.isFinite(pct)) return '0';
  const rounded = Math.round(pct * 10) / 10;
  return Number.isInteger(rounded) ? String(rounded) : rounded.toFixed(1);
}

export function plural(count: number, noun: string, suffix = 's'): string {
  return `${formatAmount(count)} ${noun}${count === 1 ? '' : suffix}`;
}

function titleCase(value: string): string {
  return value
    .toLowerCase()
    .split('_')
    .map((word) => (word ? word[0].toUpperCase() + word.slice(1) : word))
    .join(' ');
}

function pad2(value: number): string {
  return String(value).padStart(2, '0');
}
