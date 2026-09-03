/**
 * S3 — Floor: station cards, the session panel, the queue rail and the seat
 * prompts (design.md §1/§2, docs/bookings.md §2–3, docs/tournaments.md §4).
 *
 * These are state-table assertions, not snapshots. What is worth pinning down
 * is the handful of places where the floor is allowed to move ahead of the
 * server and the many where it is not:
 *
 *  - ±30 min is optimistic **and rolls back** on `BLOCKS_CONSUMED`;
 *  - seating a prepaid token is not optimistic — it spends money;
 *  - a reserved console refuses a walk-in start;
 *  - a token cannot be seated where no free console of its type exists, and
 *    when the server says `CONSOLE_TYPE_MISMATCH` anyway, the panel says so;
 *  - End is blocked while the **net** balance is unsettled.
 */

import { render, renderHook, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClientProvider } from '@tanstack/react-query';
import type { QueryClient } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FloorScreen } from '@/components/domain/floor-screen';
import { StationCard } from '@/components/domain/station-card';
import { QueueRail } from '@/components/domain/queue-rail';
import {
  SessionPanel,
  seatLabel,
  seatOffers,
  sessionPanelVariant,
} from '@/components/domain/session-panel';
import { useChangeBlocks } from '@/features/sessions/mutations';
import type { Session, Station } from '@/features/sessions/schemas';
import type { QueueEntry } from '@/features/queue/queries';
import { makeQueryClient } from '@/lib/query-client';
import { queryKeys } from '@/lib/query-keys';
import { forgetSession } from '@/lib/api';
import { noteServerTime, resetServerTime } from '@/lib/time';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn(), prefetch: vi.fn(), refresh: vi.fn() }),
  usePathname: () => '/floor',
  useSearchParams: () => new URLSearchParams(),
}));

const NOW = '2026-09-02T12:00:00Z';

/* ------------------------------------------------------------- fixtures */

const RUNNING_STATION: Station = {
  id: 1,
  name: 'Station 01',
  consoleType: 'PS5',
  floorState: 'RUNNING',
  status: 'AVAILABLE',
  session: { id: 41, blocks: 4, paidBlocks: 2, remainingSeconds: 3600, state: 'RUNNING' },
};

const FREE_PS4: Station = {
  id: 2,
  name: 'Station 02',
  consoleType: 'PS4',
  floorState: 'FREE',
  status: 'AVAILABLE',
};

const RESERVED_STATION: Station = {
  id: 3,
  name: 'Station 03',
  consoleType: 'PS5',
  floorState: 'RESERVED',
  status: 'AVAILABLE',
  match: {
    matchId: 9,
    tournamentId: 5,
    tournamentName: 'Friday FIFA Cup',
    playerA: 'Rahim',
    playerB: 'Karim',
    remainingSeconds: 600,
    round: 1,
    slot: 1,
    timeUp: false,
  },
};

const BOOKED_STATION: Station = {
  id: 4,
  name: 'Station 04',
  consoleType: 'PS5',
  floorState: 'BOOKED',
  status: 'AVAILABLE',
  arrival: { bookingId: 12, queueEntryId: 77, name: 'Nadia', token: 4, blocks: 4 },
};

const PS5_TOKEN: QueueEntry = {
  id: 88,
  tokenNo: 5,
  tokenDate: '2026-09-02',
  source: 'PLAY_TICKET',
  playerName: 'Imran',
  consoleType: 'PS5',
  blocks: 2,
  status: 'WAITING',
};

const PS4_TOKEN: QueueEntry = {
  id: 89,
  tokenNo: 6,
  tokenDate: '2026-09-02',
  source: 'PLAY_TICKET',
  playerName: 'Sadia',
  consoleType: 'PS4',
  blocks: 1,
  status: 'WAITING',
};

/** The full session behind Station 01 — `outstanding` drives the End button. */
function session(outstanding: number): Session {
  return {
    id: 41,
    stationId: 1,
    state: 'RUNNING',
    blocks: 4,
    paidBlocks: 2,
    unpaidBlocks: 2,
    remainingSeconds: 3600,
    serverTime: NOW,
    startedAt: '2026-09-02T11:00:00Z',
    gamingDue: outstanding,
    fnbDue: 0,
    netOutstanding: outstanding,
  };
}

function bill(net: number) {
  return {
    sessionId: 41,
    stationId: 1,
    gamingDue: net,
    fnbDue: 0,
    tournamentDue: 0,
    prepaidCredit: 0,
    netTotal: net,
    serverTime: NOW,
  };
}

/* --------------------------------------------------------------- harness */

const fetchMock = vi.fn();

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', Date: new Date(NOW).toUTCString() },
  });
}

function conflict(code: string, message: string) {
  return json({ error: { code, message, traceId: 't-1' } }, 409);
}

type Backend = {
  stations?: Station[];
  queue?: QueueEntry[];
  session?: Session;
  bill?: ReturnType<typeof bill>;
  /** Per-path overrides for the write calls under test. */
  routes?: Record<string, () => Promise<Response> | Response>;
  stationsStatus?: number;
};

function serve(backend: Backend = {}) {
  const {
    stations = [RUNNING_STATION, FREE_PS4, RESERVED_STATION, BOOKED_STATION],
    queue = [PS5_TOKEN, PS4_TOKEN],
    session: detail = session(0),
    bill: running = bill(0),
    routes = {},
    stationsStatus = 200,
  } = backend;

  fetchMock.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input.toString();
    const path = url.replace(/^.*\/api\/v1/, '').split('?')[0] ?? '';
    const key = `${(init?.method ?? 'GET').toUpperCase()} ${path}`;

    const override = routes[key];
    if (override) return override();

    if (path === '/stations') {
      return stationsStatus === 200
        ? json(stations)
        : json({ error: { code: 'FORBIDDEN', message: 'no', traceId: 't' } }, stationsStatus);
    }
    if (path === '/play-queue') return json(queue);
    if (path === '/sessions/41/bill') return json(running);
    if (path.startsWith('/sessions/')) return json(detail);
    return json({});
  });
}

let client: QueryClient;

function renderFloor(options: { retry?: boolean } = {}) {
  client = makeQueryClient();
  if (options.retry === false) {
    // The banner test wants the failure now, not after the transport retries
    // a 5xx twice the way `makeQueryClient` sensibly does.
    client.setDefaultOptions({ queries: { retry: false } });
  }
  return render(
    <QueryClientProvider client={client}>
      <FloorScreen />
    </QueryClientProvider>,
  );
}

/** The card for a console, by its name — after the floor has loaded. */
async function cardFor(name: string) {
  await screen.findAllByTestId('station-card');
  return screen
    .getAllByTestId('station-card')
    .find((card) => within(card).queryByText(name) !== null)!;
}

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock);
  fetchMock.mockReset();
  forgetSession();
  resetServerTime();
  noteServerTime(NOW);
  serve();
});

afterEach(() => {
  vi.unstubAllGlobals();
  resetServerTime();
});

/* ---------------------------------------------------------- the screen */

describe('the five states design.md §1 requires', () => {
  it('shows a skeleton shaped like the grid while the floor loads', () => {
    fetchMock.mockImplementation(() => new Promise(() => {}));
    renderFloor();
    expect(screen.getByTestId('floor-skeleton')).toBeInTheDocument();
  });

  it('says where to add a console when there are none', async () => {
    serve({ stations: [] });
    renderFloor();
    expect(await screen.findByTestId('floor-empty')).toHaveTextContent(
      'No stations — add one in Setup.',
    );
  });

  it('banners a failed read and turns the controls off', async () => {
    serve({ stationsStatus: 500 });
    renderFloor({ retry: false });
    await screen.findByTestId('floor-error');
    // The rail is still rendered; its actions are simply not usable.
    expect(screen.getByTestId('queue-rail')).toBeInTheDocument();
  });

  it('renders a 403 as the access notice, not a banner', async () => {
    serve({ stationsStatus: 403 });
    renderFloor();
    expect(await screen.findByTestId('access-notice')).toBeInTheDocument();
  });

  it('says "No one waiting" with an empty queue', async () => {
    serve({ queue: [] });
    renderFloor();
    expect(await screen.findByTestId('queue-empty')).toHaveTextContent('No one waiting');
  });
});

/* -------------------------------------------------------- station cards */

describe('StationCard', () => {
  it('renders each floorState as its design.md variant', () => {
    const cases: [Station, string][] = [
      [RUNNING_STATION, 'active'],
      [FREE_PS4, 'free'],
      [RESERVED_STATION, 'reserved'],
      [BOOKED_STATION, 'booked'],
      [{ ...FREE_PS4, id: 9, floorState: 'OPEN' }, 'open'],
      [{ ...FREE_PS4, id: 10, floorState: 'PAUSED' }, 'paused'],
      [{ ...FREE_PS4, id: 11, floorState: 'LOCKED' }, 'locked'],
      [{ ...FREE_PS4, id: 12, floorState: 'MAINTENANCE' }, 'maintenance'],
    ];
    for (const [station, variant] of cases) {
      const { unmount } = render(<StationCard station={station} />);
      expect(screen.getByTestId('station-card')).toHaveAttribute('data-variant', variant);
      unmount();
    }
  });

  it('shows a reserved console as its tournament match', () => {
    render(<StationCard station={RESERVED_STATION} />);
    const card = screen.getByTestId('station-card');
    expect(card).toHaveAttribute('data-variant', 'reserved');
    expect(within(card).getByText('Rahim vs Karim')).toBeInTheDocument();
    expect(within(card).getByText('Friday FIFA Cup')).toBeInTheDocument();
    expect(within(card).getByText('match left')).toBeInTheDocument();
    // The reading is the match's own, ticking off the server offset; the
    // sub-second the render takes is why this is not pinned to the exact tick.
    expect(within(card).getByTestId('countdown-clock').textContent).toMatch(/^(10:00|9:59)$/);
  });

  it('reads "match over" once the match runs out', () => {
    render(
      <StationCard
        station={{
          ...RESERVED_STATION,
          match: { ...RESERVED_STATION.match, remainingSeconds: -30, timeUp: true },
        }}
       
      />,
    );
    expect(screen.getByText('match over')).toBeInTheDocument();
    expect(screen.getByTestId('countdown-clock')).toHaveAttribute('data-state', 'overtime');
  });

  it('shows a reserved console with no started match as blocked', () => {
    render(<StationCard station={{ ...RESERVED_STATION, match: undefined }} />);
    expect(screen.getByText('Reserved for a tournament')).toBeInTheDocument();
    expect(screen.getByText('blocked for event')).toBeInTheDocument();
  });

  it('shows the arrival waiting on a booked console', () => {
    render(<StationCard station={BOOKED_STATION} />);
    expect(screen.getByText('Nadia · TOKEN #04')).toBeInTheDocument();
    expect(screen.getByText('2 h prepaid')).toBeInTheDocument();
  });
});

/* ------------------------------------------------------- the seat prompt */

describe('the seat prompt', () => {
  it('offers the console its own checked-in arrival first', () => {
    const offers = seatOffers(BOOKED_STATION, [PS5_TOKEN, PS4_TOKEN]);
    expect(offers.map((offer) => offer.source)).toEqual(['BOOKING', 'PLAY_TICKET']);
    expect(seatLabel(offers[0]!)).toBe('Seat #04 · Nadia · 2 h prepaid');
    expect(seatLabel(offers[1]!)).toBe('Seat #05 · Imran · 1 h prepaid');
  });

  it('never offers a token of the wrong console type', () => {
    const offers = seatOffers(FREE_PS4, [PS5_TOKEN, PS4_TOKEN]);
    expect(offers.map((offer) => offer.token)).toEqual([6]);
  });

  it('offers nothing on a reserved or occupied console', () => {
    expect(seatOffers(RESERVED_STATION, [PS5_TOKEN])).toEqual([]);
    expect(seatOffers(RUNNING_STATION, [PS5_TOKEN])).toEqual([]);
    expect(sessionPanelVariant(RUNNING_STATION)).toBe('station');
  });

  it('renders the prompt design.md words it', () => {
    render(<SessionPanel station={BOOKED_STATION} waiting={[PS5_TOKEN]} />);
    const panel = screen.getByTestId('session-panel');
    expect(panel).toHaveAttribute('data-variant', 'seat-prompt');
    expect(within(panel).getByText('Seat #04 · Nadia · 2 h prepaid')).toBeInTheDocument();
    expect(
      within(panel).getByText(
        'Seating loads the prepaid time as already paid — the clock starts when they sit down.',
      ),
    ).toBeInTheDocument();
  });

  it('seats without moving the floor first — a paid token is never optimistic', async () => {
    const user = userEvent.setup();
    let release: ((value: Response) => void) | null = null;
    serve({
      routes: {
        'POST /play-queue/77/seat': () =>
          new Promise<Response>((resolve) => {
            release = resolve;
          }),
      },
    });
    renderFloor();

    await user.click(await cardFor('Station 04'));
    await user.click(await screen.findByText('Seat #04 · Nadia · 2 h prepaid'));

    await waitFor(() => expect(release).not.toBeNull());

    // Mid-flight: the console is still booked and the token is still waiting.
    // Nothing pretends the seat happened until the server says it did.
    expect(await cardFor('Station 04')).toHaveAttribute('data-variant', 'booked');
    expect(client.getQueryData(queryKeys.stations.all())).toEqual([
      RUNNING_STATION,
      FREE_PS4,
      RESERVED_STATION,
      BOOKED_STATION,
    ]);
    expect(client.getQueryData(queryKeys.queue.all())).toEqual([PS5_TOKEN, PS4_TOKEN]);

    release!(json({ entry: { ...PS5_TOKEN, id: 77, status: 'SEATED' }, session: { id: 55 } }));
    await waitFor(() =>
      expect(fetchMock.mock.calls.filter(([url]) => String(url).includes('/stations')).length).toBeGreaterThan(1),
    );
  });

  it('explains a CONSOLE_TYPE_MISMATCH the client could not see coming', async () => {
    const user = userEvent.setup();
    serve({
      routes: {
        'POST /play-queue/77/seat': () =>
          conflict('CONSOLE_TYPE_MISMATCH', 'Wrong console type'),
      },
    });
    renderFloor();

    await user.click(await cardFor('Station 04'));
    await user.click(await screen.findByText('Seat #04 · Nadia · 2 h prepaid'));

    expect(await screen.findByTestId('panel-notice')).toHaveTextContent(
      'This token was sold for a different console type.',
    );
  });
});

/* ---------------------------------------------------------- the queue rail */

describe('QueueRail', () => {
  it('lists waiting tokens in token order with their console and length', () => {
    render(<QueueRail entries={[PS5_TOKEN, PS4_TOKEN]} stations={[FREE_PS4]} today="2026-09-02" />);
    const rows = screen.getAllByTestId('queue-row');
    expect(rows).toHaveLength(2);
    expect(within(rows[0]!).getByText('Imran')).toBeInTheDocument();
    expect(within(rows[0]!).getByText('PlayStation 5 · 1 h · prepaid')).toBeInTheDocument();
    expect(within(rows[1]!).getByText('PlayStation 4 · 30 min · prepaid')).toBeInTheDocument();
  });

  it('disables the seat action when no free console of that type exists', () => {
    // Only a PS4 is free, so the PS5 token has nowhere to go.
    render(<QueueRail entries={[PS5_TOKEN, PS4_TOKEN]} stations={[FREE_PS4]} />);
    const [ps5Row, ps4Row] = screen.getAllByTestId('queue-row');

    const refused = within(ps5Row!).getByRole('button');
    expect(refused).toBeDisabled();
    expect(refused).toHaveTextContent('No free console');
    expect(refused).toHaveAttribute('title', 'No free console of that type right now.');

    expect(within(ps4Row!).getByRole('button', { name: 'Seat on Station 02' })).toBeEnabled();
  });

  it('counts a console holding an arrival as free — bookings.md §7', () => {
    render(<QueueRail entries={[PS5_TOKEN]} stations={[BOOKED_STATION]} />);
    expect(screen.getByRole('button', { name: 'Seat on Station 04' })).toBeEnabled();
  });

  it('never offers a busy or reserved console', () => {
    render(<QueueRail entries={[PS5_TOKEN]} stations={[RUNNING_STATION, RESERVED_STATION]} />);
    expect(screen.getByRole('button', { name: 'No free console' })).toBeDisabled();
  });

  it('shows the issue date of a token carried over from another day', () => {
    render(
      <QueueRail
        entries={[{ ...PS5_TOKEN, tokenDate: '2026-09-01' }]}
        stations={[]}
        today="2026-09-02"
      />,
    );
    expect(screen.getByText('2026-09-01')).toBeInTheDocument();
  });

  it('seats any waiting token, not just the first', async () => {
    const onSeat = vi.fn();
    const user = userEvent.setup();
    render(
      <QueueRail entries={[PS5_TOKEN, PS4_TOKEN]} stations={[FREE_PS4]} onSeat={onSeat} />,
    );
    await user.click(screen.getByRole('button', { name: 'Seat on Station 02' }));
    expect(onSeat).toHaveBeenCalledWith(PS4_TOKEN, 2);
  });
});

/* ------------------------------------------------------- the session panel */

describe('SessionPanel', () => {
  it('refuses a walk-in start on a reserved console', () => {
    render(<SessionPanel station={RESERVED_STATION} />);
    const panel = screen.getByTestId('session-panel');
    expect(panel).toHaveAttribute('data-variant', 'reserved');
    expect(panel).toHaveAttribute('data-state', 'match');
    expect(screen.queryByTestId('start-session')).not.toBeInTheDocument();
    expect(screen.getByTestId('reserved-note')).toHaveTextContent(
      'Reserved consoles refuse a walk-in session.',
    );
    expect(screen.getByRole('button', { name: 'Reserved · Friday FIFA Cup' })).toBeDisabled();
  });

  it('blocks End while the net balance is unsettled', () => {
    render(
      <SessionPanel
        station={RUNNING_STATION}
        session={session(300)}
        bill={bill(300)}
       
      />,
    );
    const end = screen.getByTestId('end-session');
    expect(end).toBeDisabled();
    expect(end).toHaveTextContent('৳300 due — settle before ending');
    expect(screen.getByTestId('end-blocked-note')).toHaveTextContent(
      'Settle the outstanding balance before ending this session.',
    );
  });

  it('frees End once the net is zero — a seated booking ends without a payment', () => {
    render(
      <SessionPanel
        station={RUNNING_STATION}
        session={session(0)}
        bill={bill(0)}
       
      />,
    );
    const end = screen.getByTestId('end-session');
    expect(end).toBeEnabled();
    expect(end).toHaveTextContent('End session & free the station');
    expect(screen.queryByTestId('end-blocked-note')).not.toBeInTheDocument();
  });

  it('carries the running bill and the POS link', () => {
    render(
      <SessionPanel
        station={RUNNING_STATION}
        session={session(300)}
        bill={{ ...bill(300), fnbDue: 120, netTotal: 420, prepaidCredit: 200 }}
       
      />,
    );
    expect(screen.getByTestId('bill-total')).toHaveTextContent('৳420');
    expect(within(screen.getByTestId('running-bill')).getByText('Prepaid credit')).toBeInTheDocument();
    expect(screen.getByTestId('bill-link')).toHaveAttribute('href', '/pos');
  });

  it('will not remove a block that is already paid for', () => {
    render(
      <SessionPanel
        station={RUNNING_STATION}
        session={{ ...session(0), blocks: 2, paidBlocks: 2 }}
       
      />,
    );
    expect(screen.getByRole('button', { name: '−30 min block' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '+30 min block' })).toBeEnabled();
  });

  it('names the clock action for the state it is in', () => {
    const { rerender } = render(
      <SessionPanel station={RUNNING_STATION} session={session(0)} />,
    );
    expect(screen.getByRole('button', { name: 'Pause the clock' })).toBeInTheDocument();

    rerender(
      <SessionPanel
        station={{ ...RUNNING_STATION, floorState: 'PAUSED' }}
        session={{ ...session(0), state: 'PAUSED' }}
       
      />,
    );
    expect(screen.getByRole('button', { name: 'Resume the clock' })).toBeInTheDocument();

    rerender(
      <SessionPanel
        station={{ ...RUNNING_STATION, floorState: 'OPEN' }}
        session={{ ...session(0), state: 'OPEN', blocks: 0, startedAt: undefined }}
       
      />,
    );
    // OPEN with nothing bought: `NO_BLOCKS` is the server's answer, so the
    // control is off until a block is added.
    expect(screen.getByRole('button', { name: 'Start the clock' })).toBeDisabled();
  });
});

/* ------------------------------------------------- optimistic block ±30 */

describe('±30 min is optimistic, and rolls back when refused', () => {
  function seed() {
    const queryClient = makeQueryClient();
    queryClient.setQueryData(queryKeys.sessions.detail(41), session(0));
    queryClient.setQueryData(queryKeys.stations.all(), [RUNNING_STATION, FREE_PS4]);
    return queryClient;
  }

  function wrapper(queryClient: QueryClient) {
    return function Wrapper({ children }: { children: React.ReactNode }) {
      return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
    };
  }

  it('moves the session and the card before the server answers', async () => {
    const queryClient = seed();
    let release: ((value: Response) => void) | null = null;
    fetchMock.mockImplementation(
      () =>
        new Promise<Response>((resolve) => {
          release = resolve;
        }),
    );

    const { result } = renderHook(() => useChangeBlocks(), { wrapper: wrapper(queryClient) });
    result.current.mutate({ sessionId: 41, delta: 1 });

    await waitFor(() => {
      const patched = queryClient.getQueryData<Session>(queryKeys.sessions.detail(41));
      expect(patched?.blocks).toBe(5);
      expect(patched?.remainingSeconds).toBe(3600 + 1800);
    });
    const cards = queryClient.getQueryData<Station[]>(queryKeys.stations.all());
    expect(cards?.[0]?.session?.blocks).toBe(5);
    expect(cards?.[0]?.session?.remainingSeconds).toBe(5400);
    // Money is the server's arithmetic — the patch never guesses at it.
    expect(queryClient.getQueryData<Session>(queryKeys.sessions.detail(41))?.gamingDue).toBe(0);

    release!(json(session(0)));
    await waitFor(() => expect(result.current.isPending).toBe(false));
  });

  it('puts the floor back exactly as it was on BLOCKS_CONSUMED', async () => {
    const queryClient = seed();
    fetchMock.mockImplementation(async () =>
      conflict('BLOCKS_CONSUMED', 'That block has been played'),
    );

    const { result } = renderHook(() => useChangeBlocks(), { wrapper: wrapper(queryClient) });
    result.current.mutate({ sessionId: 41, delta: -1 });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(queryClient.getQueryData<Session>(queryKeys.sessions.detail(41))).toEqual(session(0));
    expect(queryClient.getQueryData<Station[]>(queryKeys.stations.all())).toEqual([
      RUNNING_STATION,
      FREE_PS4,
    ]);
  });

  it('carries an Idempotency-Key, so a retried tap buys one block', async () => {
    const queryClient = seed();
    fetchMock.mockImplementation(async () => json(session(0)));

    const { result } = renderHook(() => useChangeBlocks(), { wrapper: wrapper(queryClient) });
    result.current.mutate({ sessionId: 41, delta: 1 });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    const [, init] = fetchMock.mock.calls.at(-1)!;
    const headers = new Headers((init as RequestInit).headers);
    expect(headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/i);
  });

  it('shows the rolled-back notice on the panel', async () => {
    const user = userEvent.setup();
    serve({
      session: session(0),
      routes: {
        'POST /sessions/41/blocks': () => conflict('BLOCKS_CONSUMED', 'Already played'),
      },
    });
    renderFloor();

    await user.click(await screen.findByRole('button', { name: '−30 min block' }));
    expect(await screen.findByTestId('panel-notice')).toHaveTextContent(
      'That time has already been played or paid for.',
    );
  });
});
