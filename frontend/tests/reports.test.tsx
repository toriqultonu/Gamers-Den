/**
 * S9 — Reports (design.md §1, S9 row; docs/bookings.md §6).
 *
 * State-table assertions, not snapshots. What is pinned here:
 *
 *  - **Manager+.** The middleware keeps a cashier off `/reports`, the screen
 *    renders the access notice for one that arrives anyway, and the API's own
 *    403 renders the same notice (§4.3);
 *  - the **empty-chart states**, one per chart: design.md §1 gives S9 "Not
 *    enough data yet" *per chart*, so a venue with takings but no bookings
 *    reads a full trend beside an empty booking panel. The two facts that
 *    decide it come from the server — `tradingSeconds` and
 *    `bookings.showRatePct` — and are not inferred from zeroes;
 *  - the range window is a **request hint**; what the screen prints is the
 *    range the server says it used;
 *  - the arithmetic the charts read off the document: stacking, utilisation
 *    order, which hours count as busiest, bookings per day, the show rate.
 */

import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { NextRequest } from 'next/server';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ReportsScreen } from '@/components/domain/reports-screen';
import { middleware } from '@/middleware';
import { SESSION_COOKIE } from '@/lib/session-cookie';
import { makeQueryClient } from '@/lib/query-client';
import { forgetSession, resetIdempotencyKeys } from '@/lib/api';
import { noteServerTime, resetServerTime } from '@/lib/time';
import {
  bookingsPerDay,
  busiestHours,
  dayLabel,
  formatPct,
  hasBookingData,
  hasUtilisationData,
  hourWindow,
  rangeNote,
  rangeParams,
  showRateNote,
  showRateValue,
  stackedTrend,
  stationsBusyLabel,
  trendPeak,
  utilisationRows,
  type Report,
} from '@/features/reports/schemas';
import type { Role } from '@/lib/nav';

const NOW = '2026-09-03T14:00:00Z';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn(), prefetch: vi.fn(), refresh: vi.fn() }),
  usePathname: () => '/reports',
  useSearchParams: () => new URLSearchParams(),
}));

/* ------------------------------------------------------------- fixtures */

const REPORT: Report = {
  range: { from: '2026-08-21', to: '2026-09-03', days: 14 },
  serverTime: NOW,
  kpis: {
    revenue: 84_000,
    gaming: 52_000,
    fnb: 18_000,
    tournament: 9_000,
    booking: 6_000,
    pointsRedeemed: 1_000,
    expenses: 7_400,
    netProfit: 76_600,
    transactions: 210,
    sales: 198,
    avgTicket: 424,
    sessions: 260,
  },
  trend: [
    {
      date: '2026-09-01',
      revenue: 6_000,
      gaming: 4_000,
      fnb: 1_000,
      tournament: 700,
      booking: 300,
    },
    {
      date: '2026-09-02',
      revenue: 9_000,
      gaming: 6_000,
      fnb: 2_000,
      tournament: 500,
      booking: 500,
    },
  ],
  tradingSeconds: 100_000,
  stationUtilisation: [
    {
      stationId: 2,
      name: 'Nova',
      consoleType: 'PS4',
      underMaintenance: false,
      sessions: 40,
      busySeconds: 30_000,
      utilisationPct: 30,
    },
    {
      stationId: 1,
      name: 'Titan',
      consoleType: 'PS5',
      underMaintenance: false,
      sessions: 90,
      busySeconds: 62_000,
      utilisationPct: 62,
    },
    {
      stationId: 3,
      name: 'Vega',
      consoleType: 'PS5',
      underMaintenance: true,
      sessions: 0,
      busySeconds: 0,
      utilisationPct: 0,
    },
  ],
  busiestHours: [
    { hour: 3, revenue: 0, sales: 0, busySeconds: 0, avgStationsBusy: 0 },
    { hour: 18, revenue: 6_410, sales: 40, busySeconds: 40_000, avgStationsBusy: 3.7 },
    { hour: 14, revenue: 3_860, sales: 26, busySeconds: 24_000, avgStationsBusy: 2.4 },
    { hour: 23, revenue: 0, sales: 0, busySeconds: 900, avgStationsBusy: 0.1 },
  ],
  topSellers: [
    { itemId: 1, name: 'Cola 500ml', category: 'BEVERAGE', units: 140, revenue: 8_400 },
    { itemId: 3, name: 'Chicken Roll', category: 'FOOD', units: 60, revenue: 7_200 },
  ],
  bookings: {
    perDay: [
      { date: '2026-09-01', booked: 1, used: 2, cancelled: 0, arrived: 0, expired: 0 },
      { date: '2026-09-02', booked: 0, used: 3, cancelled: 1, arrived: 1, expired: 1 },
    ],
    booked: 1,
    used: 5,
    cancelled: 1,
    arrived: 1,
    expired: 1,
    showRatePct: 71.4,
    sold: 9,
    playIncome: 5_400,
    packageFeeIncome: 900,
    income: 6_300,
  },
};

/** A venue that has traded but has no till hours, no bookings and no sellers. */
const EMPTY_REPORT: Report = {
  range: { from: '2026-08-21', to: '2026-09-03', days: 14 },
  serverTime: NOW,
  kpis: {
    revenue: 0,
    gaming: 0,
    fnb: 0,
    tournament: 0,
    booking: 0,
    pointsRedeemed: 0,
    expenses: 0,
    netProfit: 0,
    transactions: 0,
    sales: 0,
    avgTicket: 0,
    sessions: 0,
  },
  trend: [
    { date: '2026-09-01', revenue: 0, gaming: 0, fnb: 0, tournament: 0, booking: 0 },
    { date: '2026-09-02', revenue: 0, gaming: 0, fnb: 0, tournament: 0, booking: 0 },
  ],
  tradingSeconds: 0,
  stationUtilisation: [
    {
      stationId: 1,
      name: 'Titan',
      consoleType: 'PS5',
      underMaintenance: false,
      sessions: 0,
      busySeconds: 0,
      utilisationPct: 0,
    },
  ],
  busiestHours: [{ hour: 12, revenue: 0, sales: 0, busySeconds: 0, avgStationsBusy: 0 }],
  topSellers: [],
  bookings: {
    perDay: [{ date: '2026-09-01', booked: 0, used: 0, cancelled: 0, arrived: 0, expired: 0 }],
    booked: 0,
    used: 0,
    cancelled: 0,
    arrived: 0,
    expired: 0,
    sold: 0,
    playIncome: 0,
    packageFeeIncome: 0,
    income: 0,
  },
};

/* --------------------------------------------------------------- server */

const fetchMock = vi.fn();

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', Date: new Date(NOW).toUTCString() },
  });
}

function errorBody(code: string, message = 'no') {
  return { error: { code, message, traceId: 't-1' } };
}

type Handlers = { report?: (query: URLSearchParams) => Response };

const calls: { method: string; path: string; query: string }[] = [];

function serve(handlers: Handlers = {}) {
  fetchMock.mockImplementation((input: RequestInfo, init?: RequestInit) => {
    const url = new URL(String(input));
    const path = url.pathname.replace('/api/v1', '');
    const method = (init?.method ?? 'GET').toUpperCase();
    calls.push({ method, path, query: url.searchParams.toString() });

    if (path === '/reports') return handlers.report?.(url.searchParams) ?? json(REPORT);
    return json({});
  });
}

let client: QueryClient;

function renderScreen(role: Role | null = 'MANAGER') {
  client = makeQueryClient();
  client.setDefaultOptions({ queries: { retry: false } });
  return render(
    <QueryClientProvider client={client}>
      <ReportsScreen role={role} />
    </QueryClientProvider>,
  );
}

async function openScreen(role: Role | null = 'MANAGER') {
  renderScreen(role);
  await waitFor(() => expect(screen.getByText('Net profit')).toBeInTheDocument());
}

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock);
  fetchMock.mockReset();
  calls.length = 0;
  forgetSession();
  resetServerTime();
  resetIdempotencyKeys();
  serve();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

/* ------------------------------------------------------------------ pure */

describe('the range', () => {
  it('asks for inclusive venue days ending today, off the server clock', () => {
    noteServerTime(new Date(NOW).toUTCString());

    // 14:00Z is 20:00 in Dhaka — the same venue day, and thirteen days back.
    expect(rangeParams('14d')).toEqual({ from: '2026-08-21', to: '2026-09-03' });
    expect(rangeParams('7d')).toEqual({ from: '2026-08-28', to: '2026-09-03' });
    expect(rangeParams('30d')).toEqual({ from: '2026-08-05', to: '2026-09-03' });
  });

  it('prints the window the server says it used, not the one that was asked for', () => {
    expect(rangeNote(REPORT.range)).toBe('21 Aug – 3 Sept · 14 days');
    expect(rangeNote(undefined)).toBe('');
  });

  it('reads a venue day as its own date whatever the terminal’s timezone', () => {
    expect(dayLabel('2026-08-21')).toBe('21 Aug');
    expect(dayLabel(undefined)).toBe('');
  });
});

describe('the stacked trend', () => {
  it('stacks all four money buckets, not the prototype’s two', () => {
    const days = stackedTrend(REPORT.trend);

    expect(days[0].segments.map((segment) => segment.key)).toEqual([
      'gaming',
      'fnb',
      'tournament',
      'booking',
    ]);
    expect(days[0].total).toBe(6_000);
    expect(days[1].total).toBe(9_000);
    expect(trendPeak(days)).toBe(9_000);
  });

  it('has nothing to draw when every day is zero', () => {
    expect(trendPeak(stackedTrend(EMPTY_REPORT.trend))).toBe(0);
    expect(trendPeak(stackedTrend(undefined))).toBe(0);
  });
});

describe('utilisation', () => {
  it('ranks busiest first and keeps the console nobody used', () => {
    expect(utilisationRows(REPORT.stationUtilisation).map((row) => row.name)).toEqual([
      'Titan',
      'Nova',
      'Vega',
    ]);
  });

  it('says how long each was busy, and that a bench console was on the bench', () => {
    const rows = utilisationRows(REPORT.stationUtilisation);
    expect(rows[0].note).toBe('17 h 13 min · 90 sessions');
    expect(rows[2].note).toBe('0 min · in service');
  });

  it('is a share of nothing — and so an empty chart — with no till hours', () => {
    expect(hasUtilisationData(REPORT)).toBe(true);
    expect(hasUtilisationData(EMPTY_REPORT)).toBe(false);
    expect(hasUtilisationData(undefined)).toBe(false);
  });
});

describe('busiest hours', () => {
  it('keeps only the hours that traded, takings first', () => {
    expect(busiestHours(REPORT.busiestHours).map((row) => row.window)).toEqual([
      '18:00 – 19:00',
      '14:00 – 15:00',
      // Occupied but took nothing — still a busy hour, ranked below the money.
      '23:00 – 00:00',
    ]);
  });

  it('wraps midnight rather than printing a 24th hour', () => {
    expect(hourWindow(23)).toBe('23:00 – 00:00');
    expect(hourWindow(0)).toBe('00:00 – 01:00');
  });

  it('reads occupancy against the seats there are', () => {
    expect(stationsBusyLabel(3.7, 4)).toBe('3.7 / 4');
    expect(busiestHours(EMPTY_REPORT.busiestHours)).toEqual([]);
  });
});

describe('the pre-booking figures (docs/bookings.md §6)', () => {
  it('counts every slot due that day, whatever became of it', () => {
    expect(bookingsPerDay(REPORT.bookings).map((day) => day.total)).toEqual([3, 6]);
  });

  it('reads the show rate as resolved seats, not as a share of everything sold', () => {
    expect(showRateValue(REPORT.bookings)).toBe('71.4%');
    expect(showRateNote(REPORT.bookings)).toBe('71.4% · 5 of 7 seats taken up');
  });

  it('calls an unresolved range unknown rather than zero', () => {
    expect(showRateValue(EMPTY_REPORT.bookings)).toBe('—');
    expect(showRateNote(EMPTY_REPORT.bookings)).toBe('Not enough data yet');
    expect(showRateNote(undefined)).toBe('Not enough data yet');
  });

  it('knows when the whole panel is empty', () => {
    expect(hasBookingData(REPORT.bookings)).toBe(true);
    expect(hasBookingData(EMPTY_REPORT.bookings)).toBe(false);
    expect(hasBookingData(undefined)).toBe(false);
  });

  it('drops a trailing .0 so a whole percentage reads as one', () => {
    expect(formatPct(50)).toBe('50');
    expect(formatPct(66.66)).toBe('66.7');
    expect(formatPct(Number.NaN)).toBe('0');
  });
});

/* ----------------------------------------------------------- the guard */

describe('S9 is Manager+', () => {
  it('lets a manager and an admin through, and turns a cashier back', () => {
    expect(visit('/reports', 'MANAGER').headers.get('location')).toBeNull();
    expect(visit('/reports', 'ADMIN').headers.get('location')).toBeNull();
    expect(redirectOf(visit('/reports', 'CASHIER'))).toBe('/floor');
  });

  it('renders the access notice — and asks the API nothing — for a cashier', async () => {
    renderScreen('CASHIER');

    expect(await screen.findByTestId('access-notice')).toBeInTheDocument();
    expect(calls.some((call) => call.path === '/reports')).toBe(false);
  });

  it('renders the access notice when the API 403s a stale cookie', async () => {
    serve({ report: () => json(errorBody('FORBIDDEN'), 403) });
    renderScreen('MANAGER');

    expect(await screen.findByTestId('access-notice')).toBeInTheDocument();
  });
});

/* ---------------------------------------------------------- the screen */

describe('the default state', () => {
  it('opens on the KPI row and the server’s own window', async () => {
    await openScreen();

    expect(screen.getByText('৳84,000')).toBeInTheDocument();
    expect(screen.getByText('৳76,600')).toBeInTheDocument();
    expect(screen.getByTestId('reports-range')).toHaveTextContent('21 Aug – 3 Sept · 14 days');
  });

  it('draws the four-segment trend with its legend', async () => {
    await openScreen();

    expect(screen.getByTestId('trend-chart')).toBeInTheDocument();
    expect(screen.getAllByTestId('trend-segment')).toHaveLength(8);
    expect(within(screen.getByTestId('trend-legend')).getByText('Pre-booking')).toBeInTheDocument();
  });

  it('lists utilisation, the busiest hours and the top sellers', async () => {
    await openScreen();

    expect(screen.getAllByTestId('utilisation-row')).toHaveLength(3);
    expect(screen.getByText('18:00 – 19:00')).toBeInTheDocument();
    expect(screen.getByText('3.7 / 3')).toBeInTheDocument();
    expect(screen.getByText('Cola 500ml')).toBeInTheDocument();
  });

  it('reports the pre-booking figures beside the bookings-per-day bars', async () => {
    await openScreen();

    expect(screen.getByText('71.4%')).toBeInTheDocument();
    expect(screen.getByText('71.4% · 5 of 7 seats taken up')).toBeInTheDocument();
    expect(screen.getByText('৳900')).toBeInTheDocument();
  });

  it('writes nothing — S9 only reads', async () => {
    await openScreen();

    expect(calls.every((call) => call.method === 'GET')).toBe(true);
  });
});

describe('the range switch', () => {
  it('re-asks with a wider window under its own cache key', async () => {
    const user = userEvent.setup();
    await openScreen();

    await user.click(screen.getByRole('radio', { name: '30 days' }));

    await waitFor(() => expect(calls.filter((call) => call.path === '/reports')).toHaveLength(2));
    expect(calls[0].query).toContain('from=');
    expect(calls[1].query).not.toBe(calls[0].query);
    expect(client.getQueryData(['reports', '30d'])).toBeDefined();
    expect(client.getQueryData(['reports', '14d'])).toBeDefined();
  });
});

describe('the loading state', () => {
  it('shows a skeleton shaped like the screen', async () => {
    let release: (() => void) | null = null;
    const held = new Promise<Response>((resolve) => {
      release = () => resolve(json(REPORT));
    });
    serve({ report: () => held as unknown as Response });

    renderScreen();

    expect(await screen.findByTestId('reports-skeleton')).toBeInTheDocument();
    release?.();
    await waitFor(() => expect(screen.queryByTestId('reports-skeleton')).not.toBeInTheDocument());
  });
});

describe('the empty states — one per chart (design.md §1)', () => {
  beforeEach(() => {
    serve({ report: () => json(EMPTY_REPORT) });
  });

  it('says "Not enough data yet" in each chart that has nothing', async () => {
    await openScreen();

    expect(screen.getByTestId('trend-empty')).toHaveTextContent('Not enough data yet');
    expect(screen.getByTestId('utilisation-empty')).toHaveTextContent('Not enough data yet');
    expect(screen.getByTestId('bookings-empty')).toHaveTextContent('Not enough data yet');
    const tables = screen.getAllByTestId('data-table-empty');
    expect(tables).toHaveLength(2);
    for (const table of tables) expect(table).toHaveTextContent('Not enough data yet');
  });

  it('leaves the KPI tiles standing — a zero is a fact, not an empty state', async () => {
    await openScreen();

    expect(screen.getByText('Revenue')).toBeInTheDocument();
    expect(screen.getAllByText('৳0').length).toBeGreaterThan(0);
  });

  it('draws a full trend beside an empty booking panel when only bookings are missing', async () => {
    serve({ report: () => json({ ...REPORT, bookings: EMPTY_REPORT.bookings }) });
    await openScreen();

    expect(screen.getByTestId('trend-chart')).toBeInTheDocument();
    expect(screen.getByTestId('bookings-empty')).toBeInTheDocument();
  });
});

describe('the error state', () => {
  it('renders the notice without destroying the range choice', async () => {
    serve({ report: () => json(errorBody('SYNC_UNAVAILABLE'), 503) });
    renderScreen();

    expect(await screen.findByTestId('reports-error')).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: '14 days' })).toHaveAttribute('aria-checked', 'true');
  });
});

/* -------------------------------------------------------------- helpers */

const ORIGIN = 'http://terminal.local';

function visit(path: string, role: Role) {
  const headers = new Headers();
  headers.set('cookie', `${SESSION_COOKIE}=${role}`);
  return middleware(new NextRequest(new URL(path, ORIGIN), { headers }));
}

function redirectOf(response: ReturnType<typeof middleware>): string | null {
  const location = response.headers.get('location');
  return location ? new URL(location).pathname : null;
}
