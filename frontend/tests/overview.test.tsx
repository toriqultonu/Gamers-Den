/**
 * S2 — Overview (design.md §1, S2 row; docs/bookings.md §6).
 *
 * State-table assertions, not snapshots. What is pinned here:
 *
 *  - **Admin only.** The middleware redirect for `/overview`, and the access
 *    notice the screen itself renders when the routing hint and the real role
 *    disagree — including the API's own 403, which is the case hiding cannot
 *    cover (§4.3);
 *  - the **pre-sold stat** — the figure and the sub-line that says what it is
 *    made of (bookings still PAID + play tickets still WAITING);
 *  - the **empty-chart states**: no revenue, no weekday takings, an empty
 *    watchlist, no closes and no consoles each say so in their own box rather
 *    than drawing zeroes;
 *  - the **alerts rail** — the bell's unread badge counts the unread rows of
 *    the `['alerts']` query, moves when one is marked read, and disappears at
 *    zero;
 *  - the five states: default, loading skeleton shaped like the grid, empty,
 *    error (the stale-data banner keeps the last good figures on screen), and
 *    permission-denied.
 */

import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { NextRequest } from 'next/server';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { OverviewScreen } from '@/components/domain/overview-screen';
import { middleware } from '@/middleware';
import { SESSION_COOKIE } from '@/lib/session-cookie';
import { makeQueryClient } from '@/lib/query-client';
import { forgetSession, resetIdempotencyKeys } from '@/lib/api';
import { resetServerTime } from '@/lib/time';
import { resetPosStore } from '@/features/pos/bill-store';
import {
  alertKindLabel,
  badgeLabel,
  discrepancyNote,
  hasWeekdayData,
  netProfitNote,
  occupancyNote,
  occupancyPct,
  preSoldNote,
  stockWatchRows,
  trendBars,
  trendSummary,
  unreadCount,
  weekdayRows,
  type Alert,
  type Overview,
} from '@/features/reports/schemas';
import type { Station } from '@/features/sessions/schemas';
import type { Role } from '@/lib/nav';

const NOW = '2026-09-03T14:00:00Z';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn(), prefetch: vi.fn(), refresh: vi.fn() }),
  usePathname: () => '/overview',
  useSearchParams: () => new URLSearchParams(),
}));

/* ------------------------------------------------------------- fixtures */

const OVERVIEW: Overview = {
  serverTime: NOW,
  date: '2026-09-03',
  occupancy: { busy: 2, stations: 4, maintenance: 1, available: 3, pct: 66.7 },
  today: {
    revenue: 12_400,
    gaming: 8_200,
    fnb: 2_100,
    tournament: 1_200,
    booking: 900,
    pointsRedeemed: 0,
    expenses: 1_180,
    netProfit: 11_220,
    transactions: 26,
    sales: 24,
    avgTicket: 516,
    sessions: 38,
  },
  preSold: {
    bookings: 3,
    bookingPlayAmount: 1_800,
    bookingPackageFee: 300,
    bookingAmount: 2_100,
    playTickets: 1,
    playTicketAmount: 400,
    amount: 2_500,
  },
  revenue30Days: {
    revenue: 392_400,
    previousRevenue: 353_500,
    days: [
      { date: '2026-09-01', revenue: 9_000, gaming: 6_000, fnb: 3_000 },
      { date: '2026-09-02', revenue: 11_000, gaming: 7_000, fnb: 4_000 },
      { date: '2026-09-03', revenue: 12_400, gaming: 8_200, fnb: 2_100 },
    ],
  },
  byDayOfWeek: [
    { day: 'MONDAY', revenue: 8_000, days: 4, average: 2_000 },
    { day: 'FRIDAY', revenue: 24_000, days: 4, average: 6_000 },
  ],
  stockWatchlist: [
    { itemId: 3, name: 'Chicken Roll', category: 'FOOD', stock: 3, reorderAt: 6 },
    { itemId: 4, name: 'HDMI cable', category: 'EXTRAS', stock: 0, reorderAt: 2 },
  ],
  recentCloses: [
    {
      shiftId: 12,
      staffId: 2,
      terminal: 'counter-1',
      openedAt: '2026-09-02T10:00:00Z',
      closedAt: '2026-09-02T18:00:00Z',
      openingFloat: 3_000,
      takings: 14_500,
      countedCash: 9_850,
      expectedCash: 10_000,
      discrepancy: -150,
    },
  ],
};

/** Nothing has happened yet — every chart's empty case in one document. */
const EMPTY_OVERVIEW: Overview = {
  serverTime: NOW,
  date: '2026-09-03',
  occupancy: { busy: 0, stations: 0, maintenance: 0, available: 0, pct: 0 },
  today: {
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
  preSold: {
    bookings: 0,
    bookingPlayAmount: 0,
    bookingPackageFee: 0,
    bookingAmount: 0,
    playTickets: 0,
    playTicketAmount: 0,
    amount: 0,
  },
  revenue30Days: { revenue: 0, previousRevenue: 0, days: [] },
  byDayOfWeek: [{ day: 'MONDAY', revenue: 0, days: 4, average: 0 }],
  stockWatchlist: [],
  recentCloses: [],
};

const STATIONS: Station[] = [
  {
    id: 1,
    name: 'Titan',
    consoleType: 'PS5',
    status: 'AVAILABLE',
    floorState: 'RUNNING',
    session: { id: 41, state: 'RUNNING', blocks: 4, paidBlocks: 2, remainingSeconds: 3_600 },
  },
  { id: 2, name: 'Nova', consoleType: 'PS4', status: 'AVAILABLE', floorState: 'FREE' },
];

const ALERTS: Alert[] = [
  {
    id: 9,
    type: 'CASH_DISCREPANCY',
    title: 'Drawer short ৳150',
    body: 'Shift #12 closed 150 under.',
    read: false,
    createdAt: '2026-09-03T12:00:00Z',
  },
  {
    id: 8,
    type: 'LOW_STOCK',
    title: 'Chicken Roll is low',
    body: '3 left, reorder point is 6.',
    read: false,
    createdAt: '2026-09-03T11:00:00Z',
  },
  {
    id: 7,
    type: 'PRINTER_FAILED',
    title: 'Ticket never printed',
    body: 'Job 22 gave up after 3 attempts.',
    read: true,
    createdAt: '2026-09-02T20:00:00Z',
  },
];

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

type Handlers = {
  overview?: () => Response;
  stations?: () => Response;
  alerts?: () => Response;
  readAll?: () => Response;
  readOne?: () => Response;
};

const calls: { method: string; path: string }[] = [];

function serve(handlers: Handlers = {}) {
  fetchMock.mockImplementation((input: RequestInfo, init?: RequestInit) => {
    const url = new URL(String(input));
    const path = url.pathname.replace('/api/v1', '');
    const method = (init?.method ?? 'GET').toUpperCase();
    calls.push({ method, path });

    if (path === '/overview') return handlers.overview?.() ?? json(OVERVIEW);
    if (path === '/stations') return handlers.stations?.() ?? json(STATIONS);
    if (path === '/alerts') return handlers.alerts?.() ?? json(ALERTS);
    if (path === '/alerts/read-all') {
      return handlers.readAll?.() ?? json(ALERTS.map((alert) => ({ ...alert, read: true })));
    }
    if (/^\/alerts\/\d+\/read$/.test(path)) {
      return handlers.readOne?.() ?? json({ ...ALERTS[0], read: true });
    }
    if (path === '/sync/status') {
      return json({ state: 'SYNCED', lastSyncedAt: '2026-09-03T13:58:00Z', pendingOps: 0 });
    }
    return json({});
  });
}

let client: QueryClient;

function renderScreen(role: Role | null = 'ADMIN') {
  client = makeQueryClient();
  client.setDefaultOptions({ queries: { retry: false } });
  return render(
    <QueryClientProvider client={client}>
      <OverviewScreen role={role} />
    </QueryClientProvider>,
  );
}

async function openScreen(role: Role | null = 'ADMIN') {
  renderScreen(role);
  await waitFor(() => expect(screen.getByText('Pre-sold')).toBeInTheDocument());
}

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock);
  fetchMock.mockReset();
  calls.length = 0;
  forgetSession();
  resetServerTime();
  resetIdempotencyKeys();
  resetPosStore();
  serve();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

/* ------------------------------------------------------------------ pure */

describe('the occupancy tile', () => {
  it('reads the server’s percentage and counts against seats that could be busy', () => {
    expect(occupancyPct(OVERVIEW.occupancy)).toBe('66.7%');
    expect(occupancyNote(OVERVIEW.occupancy)).toBe('2 of 3 seats busy · 1 in service');
  });

  it('drops the service clause when nothing is on the bench', () => {
    expect(occupancyNote({ busy: 1, stations: 4, maintenance: 0, available: 4, pct: 25 })).toBe(
      '1 of 4 seats busy',
    );
  });

  it('says there are no consoles rather than dividing by none', () => {
    expect(occupancyPct(EMPTY_OVERVIEW.occupancy)).toBe('0%');
    expect(occupancyNote(EMPTY_OVERVIEW.occupancy)).toBe('No consoles registered');
    expect(occupancyNote(undefined)).toBe('No consoles registered');
  });
});

describe('the pre-sold stat (docs/bookings.md §6)', () => {
  it('names both halves — bookings still PAID and play tickets still WAITING', () => {
    expect(preSoldNote(OVERVIEW.preSold)).toBe('3 bookings · 1 play ticket not played yet');
  });

  it('says only the half that exists', () => {
    expect(preSoldNote({ bookings: 1, playTickets: 0, amount: 700 })).toBe(
      '1 booking not played yet',
    );
    expect(preSoldNote({ bookings: 0, playTickets: 2, amount: 800 })).toBe(
      '2 play tickets not played yet',
    );
  });

  it('is a sentence, not a zero, when nothing is outstanding', () => {
    expect(preSoldNote(EMPTY_OVERVIEW.preSold)).toBe('Nothing pre-sold right now');
    expect(preSoldNote(undefined)).toBe('Nothing pre-sold right now');
  });
});

describe('the trend line', () => {
  it('compares the 30 days with the 30 before them', () => {
    expect(trendSummary(OVERVIEW.revenue30Days)).toBe('৳392,400 total · +11% on the previous 30');
  });

  it('never turns a first month into an infinite improvement', () => {
    expect(trendSummary({ revenue: 5_000, previousRevenue: 0, days: [] })).toBe(
      '৳5,000 total · nothing to compare with yet',
    );
  });

  it('signs a fall with a real minus', () => {
    expect(trendSummary({ revenue: 90, previousRevenue: 100, days: [] })).toContain('−10%');
  });

  it('turns the days into bars in the order they arrived', () => {
    expect(trendBars(OVERVIEW.revenue30Days?.days).map((bar) => bar.value)).toEqual([
      9_000, 11_000, 12_400,
    ]);
    expect(trendBars(undefined)).toEqual([]);
  });
});

describe('by day of week', () => {
  it('averages rather than totals, because a window holds four Fridays or five', () => {
    expect(weekdayRows(OVERVIEW.byDayOfWeek)).toEqual([
      { key: 'MONDAY', label: 'Mon', average: 2_000, days: 4 },
      { key: 'FRIDAY', label: 'Fri', average: 6_000, days: 4 },
    ]);
  });

  it('is empty until some weekday has taken money', () => {
    expect(hasWeekdayData(OVERVIEW.byDayOfWeek)).toBe(true);
    expect(hasWeekdayData(EMPTY_OVERVIEW.byDayOfWeek)).toBe(false);
    expect(hasWeekdayData(undefined)).toBe(false);
  });
});

describe('the watchlist and the closes', () => {
  it('says how far under each line is', () => {
    expect(stockWatchRows(OVERVIEW.stockWatchlist).map((row) => row.note)).toEqual([
      '3 left · reorder at 6',
      'None left · reorder at 2',
    ]);
  });

  it('reads a close’s drawer as short, over or balanced', () => {
    expect(discrepancyNote(OVERVIEW.recentCloses?.[0])).toBe('−৳150 short');
    expect(discrepancyNote({ shiftId: 1, discrepancy: 50 })).toBe('+৳50 over');
    expect(discrepancyNote({ shiftId: 1, discrepancy: 0 })).toBe('Balanced');
  });

  it('refuses to call an uncounted drawer balanced', () => {
    expect(discrepancyNote({ shiftId: 1 })).toBeNull();
  });

  it('names the petty cash behind the net-profit tile', () => {
    expect(netProfitNote(OVERVIEW.today)).toBe('after ৳1,180 expenses');
    expect(netProfitNote(EMPTY_OVERVIEW.today)).toBe('no petty cash today');
  });
});

describe('the alerts badge', () => {
  it('counts the unread rows of the feed the rail already holds', () => {
    expect(unreadCount(ALERTS)).toBe(2);
    expect(unreadCount(ALERTS.map((alert) => ({ ...alert, read: true })))).toBe(0);
    expect(unreadCount(undefined)).toBe(0);
  });

  it('stops counting past two digits so the 18px badge cannot reflow the rail', () => {
    expect(badgeLabel(7)).toBe('7');
    expect(badgeLabel(99)).toBe('99');
    expect(badgeLabel(140)).toBe('99+');
  });

  it('labels the three types the backend raises, and survives a fourth', () => {
    expect(alertKindLabel('CASH_DISCREPANCY')).toBe('Cash');
    expect(alertKindLabel('PRINTER_FAILED')).toBe('Printer');
    expect(alertKindLabel('LOW_STOCK')).toBe('Stock');
    expect(alertKindLabel('SYNC_BACKLOG')).toBe('Sync Backlog');
    expect(alertKindLabel(undefined)).toBe('Alert');
  });
});

/* ----------------------------------------------------------- the guard */

describe('S2 is the owner’s screen', () => {
  it('redirects a manager and a cashier off /overview before it renders', () => {
    expect(redirectOf(visit('/overview', 'MANAGER'))).toBe('/floor');
    expect(redirectOf(visit('/overview', 'CASHIER'))).toBe('/floor');
    expect(visit('/overview', 'ADMIN').headers.get('location')).toBeNull();
  });

  it('renders the access notice — and asks the API nothing — for a non-admin', async () => {
    renderScreen('MANAGER');

    expect(await screen.findByTestId('access-notice')).toBeInTheDocument();
    expect(screen.queryByTestId('overview-screen')).not.toBeInTheDocument();
    expect(calls.some((call) => call.path === '/overview')).toBe(false);
  });

  it('renders the access notice when the API 403s a stale ADMIN cookie', async () => {
    serve({ overview: () => json(errorBody('FORBIDDEN'), 403) });
    renderScreen('ADMIN');

    expect(await screen.findByTestId('access-notice')).toBeInTheDocument();
  });
});

/* ---------------------------------------------------------- the screen */

describe('the default state', () => {
  it('draws the five tiles, the pre-sold figure included', async () => {
    await openScreen();

    expect(screen.getByText('৳2,500')).toBeInTheDocument();
    expect(screen.getByText('3 bookings · 1 play ticket not played yet')).toBeInTheDocument();
    expect(screen.getByText('66.7%')).toBeInTheDocument();
    expect(screen.getByText('৳12,400')).toBeInTheDocument();
    expect(screen.getByText('৳11,220')).toBeInTheDocument();
  });

  it('scrolls the live consoles from the floor’s own read, each linking to S3', async () => {
    await openScreen();

    const cards = await screen.findAllByTestId('overview-station-card');
    expect(cards).toHaveLength(2);
    expect(cards[0]).toHaveAttribute('href', '/floor');
    expect(within(cards[0]).getByText('Titan')).toBeInTheDocument();
  });

  it('lists the watchlist and the closes with their verdicts', async () => {
    await openScreen();

    expect(screen.getAllByTestId('watchlist-row')).toHaveLength(2);
    expect(screen.getByText('3 left · reorder at 6')).toBeInTheDocument();
    expect(screen.getByTestId('shift-close-row')).toHaveTextContent('−৳150 short');
  });

  it('writes nothing — S2 only reads', async () => {
    await openScreen();

    expect(calls.every((call) => call.method === 'GET')).toBe(true);
  });
});

describe('the loading state', () => {
  it('shows a skeleton shaped like the tile grid', async () => {
    let release!: () => void;
    const held = new Promise<Response>((resolve) => {
      release = () => resolve(json(OVERVIEW));
    });
    serve({ overview: () => held as unknown as Response });

    renderScreen();

    expect(await screen.findByTestId('overview-skeleton')).toBeInTheDocument();
    release();
    await waitFor(() => expect(screen.queryByTestId('overview-skeleton')).not.toBeInTheDocument());
  });
});

describe('the empty states', () => {
  beforeEach(() => {
    serve({ overview: () => json(EMPTY_OVERVIEW), stations: () => json([]), alerts: () => json([]) });
  });

  it('says so per chart rather than drawing empty axes', async () => {
    await openScreen();

    expect(screen.getByTestId('bar-chart-empty')).toHaveTextContent('No sessions yet today');
    expect(screen.getByTestId('weekday-empty')).toHaveTextContent('Not enough data yet');
    expect(screen.getByTestId('watchlist-empty')).toBeInTheDocument();
    expect(screen.getByTestId('closes-empty')).toBeInTheDocument();
    expect(screen.getByTestId('live-stations-empty')).toBeInTheDocument();
  });

  it('leaves the tiles in place — a zero is a fact, not an empty state', async () => {
    await openScreen();

    expect(screen.getByText('Nothing pre-sold right now')).toBeInTheDocument();
    expect(screen.getByText('No sessions yet today', { selector: 'span' })).toBeInTheDocument();
  });
});

describe('the error state', () => {
  it('renders the notice when there is nothing cached to fall back on', async () => {
    serve({ overview: () => json(errorBody('SYNC_UNAVAILABLE'), 503) });
    renderScreen();

    expect(await screen.findByTestId('overview-error')).toBeInTheDocument();
    expect(screen.queryByTestId('overview-stale')).not.toBeInTheDocument();
  });

  it('keeps the last good figures behind a stale-data banner with the last sync', async () => {
    await openScreen();
    expect(screen.getByText('৳2,500')).toBeInTheDocument();

    serve({ overview: () => json(errorBody('SYNC_UNAVAILABLE'), 503) });
    await client.refetchQueries({ queryKey: ['overview'] });

    const banner = await screen.findByTestId('overview-stale');
    expect(banner).toHaveTextContent('could not be refreshed');
    expect(banner).toHaveTextContent('Last synced');
    // The figures are still on the page — the banner explains them, it does not
    // replace them (design.md §1, S2 error row).
    expect(screen.getByText('৳2,500')).toBeInTheDocument();
  });
});

/* ------------------------------------------------------- the alerts rail */

describe('the alerts rail', () => {
  it('starts collapsed on a narrow terminal and badges the unread count', async () => {
    await openScreen();

    const rail = screen.getByTestId('alerts-rail');
    expect(rail).toHaveAttribute('data-state', 'collapsed');
    expect(await within(rail).findByTestId('alerts-badge')).toHaveTextContent('2');
  });

  it('starts open once the viewport is wide enough (design.md §4)', async () => {
    stubMatchMedia(true);
    await openScreen();

    expect(screen.getByTestId('alerts-rail')).toHaveAttribute('data-state', 'expanded');
  });

  it('opens on the bell and lists the feed newest first', async () => {
    const user = userEvent.setup();
    await openScreen();

    await user.click(screen.getByRole('button', { name: /open alerts/i }));

    const cards = await screen.findAllByTestId('alert-card');
    expect(cards).toHaveLength(3);
    expect(within(cards[0]).getByText('Drawer short ৳150')).toBeInTheDocument();
    expect(cards[0]).toHaveAttribute('data-unread', 'true');
    expect(cards[2]).not.toHaveAttribute('data-unread');
  });

  it('drops the badge to zero once the bell is cleared', async () => {
    const user = userEvent.setup();
    await openScreen();

    await user.click(screen.getByRole('button', { name: /open alerts/i }));
    await user.click(await screen.findByRole('button', { name: 'Mark all read' }));

    await waitFor(() => expect(screen.queryByTestId('alerts-badge')).not.toBeInTheDocument());
    expect(calls.some((call) => call.method === 'POST' && call.path === '/alerts/read-all')).toBe(
      true,
    );
  });

  it('takes one card off the count without re-reading the feed', async () => {
    const user = userEvent.setup();
    await openScreen();

    await user.click(screen.getByRole('button', { name: /open alerts/i }));
    const [first] = await screen.findAllByTestId('alert-card');
    await user.click(within(first).getByRole('button', { name: 'Mark read' }));

    await waitFor(() => expect(screen.getByTestId('alerts-badge')).toHaveTextContent('1 unread'));
    expect(calls.filter((call) => call.path === '/alerts')).toHaveLength(1);
  });

  it('says plainly when there is nothing to report', async () => {
    serve({ alerts: () => json([]) });
    const user = userEvent.setup();
    await openScreen();

    await user.click(screen.getByRole('button', { name: /open alerts/i }));

    expect(await screen.findByTestId('alerts-empty')).toBeInTheDocument();
    expect(screen.queryByTestId('alerts-badge')).not.toBeInTheDocument();
  });

  it('reports a feed it could not read instead of showing an empty bell', async () => {
    serve({ alerts: () => json(errorBody('SYNC_UNAVAILABLE'), 503) });
    const user = userEvent.setup();
    await openScreen();

    await user.click(screen.getByRole('button', { name: /open alerts/i }));

    expect(await screen.findByTestId('alerts-error')).toBeInTheDocument();
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

/** jsdom has no `matchMedia`; the rail's default is the narrow one without it. */
function stubMatchMedia(matches: boolean) {
  vi.stubGlobal(
    'matchMedia',
    vi.fn().mockImplementation((query: string) => ({
      matches,
      media: query,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })),
  );
}
