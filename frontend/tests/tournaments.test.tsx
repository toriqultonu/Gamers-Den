/**
 * S12 — Tournaments: the bracket, the live tiles, the match board and the two
 * role rails (design.md §1 S12 + §2 component rows, docs/tournaments.md §4/§8).
 *
 * State-table assertions, not snapshots. What is pinned here is what this
 * screen is not allowed to get wrong:
 *
 *  - a perfect bracket draws **N−1 boxes** for every cap the API allows
 *    (4/8/16/32), and propagation is *displayed* — the winner carries the red
 *    W, the loser dims, and the match they advance into shows their name where
 *    it said TBD;
 *  - **+5 min re-bases the countdown** from the server's next reading, on the
 *    tile, the bracket tag and the board row together — never by adding
 *    minutes to a local clock;
 *  - a cashier gets **no manager rail** and the finance query is **never
 *    mounted** for them (docs/tournaments.md §6 — the endpoint 403s anyway);
 *  - winner recording is not optimistic: a refusal raises the banner and leaves
 *    the bracket exactly as the server last described it;
 *  - the start button explains why it is dead, and "No tournaments scheduled".
 */

import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TournamentsScreen, canManageTournaments } from '@/components/domain/tournaments-screen';
import { makeQueryClient } from '@/lib/query-client';
import { forgetSession, resetIdempotencyKeys } from '@/lib/api';
import { resetPosStore } from '@/features/pos/bill-store';
import { formatCountdown, resetServerTime } from '@/lib/time';
import {
  boardStatus,
  bracketRounds,
  canDecide,
  consoleHint,
  expectedMatchCount,
  financeFormula,
  financeVerdict,
  matchTag,
  roundLabels,
  slotsNote,
  tournamentStatusLabel,
  tournamentStatusTag,
  type ConsoleAvailability,
  type Tournament,
  type TournamentDetail,
  type TournamentEntry,
  type TournamentFinance,
  type TournamentMatch,
} from '@/features/tournaments/schemas';
import type { Station } from '@/features/sessions/queries';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn(), prefetch: vi.fn(), refresh: vi.fn() }),
  usePathname: () => '/tournaments',
  useSearchParams: () => new URLSearchParams(),
}));

const NOW = '2026-09-03T12:00:00Z';

/* ------------------------------------------------------------- fixtures */

const STATIONS: Station[] = [
  { id: 1, name: 'Nexus', consoleType: 'PS5', floorState: 'RESERVED', status: 'AVAILABLE' },
  { id: 2, name: 'Vortex', consoleType: 'PS5', floorState: 'FREE', status: 'AVAILABLE' },
];

/**
 * A perfect bracket for a cap, the way the server sends one: every match with
 * its round, slot and the `next_match_id` it advances along, first-round
 * players seeded in sale order and later rounds empty until they are played
 * for.
 */
function perfectBracket(cap: number): TournamentMatch[] {
  const rounds = Math.log2(cap);
  const matches: TournamentMatch[] = [];
  let id = 1;
  const idsByRound: number[][] = [];

  for (let round = 1; round <= rounds; round += 1) {
    const ids: number[] = [];
    for (let slot = 1; slot <= cap / 2 ** round; slot += 1) ids.push(id++);
    idsByRound.push(ids);
  }

  for (let round = 1; round <= rounds; round += 1) {
    const ids = idsByRound[round - 1];
    ids.forEach((matchId, index) => {
      const next = idsByRound[round]?.[Math.floor(index / 2)] ?? undefined;
      matches.push({
        id: matchId,
        round,
        slot: index + 1,
        nextMatchId: next,
        entryA: round === 1 ? index * 2 + 1 : undefined,
        entryB: round === 1 ? index * 2 + 2 : undefined,
        playerA: round === 1 ? `Player ${index * 2 + 1}` : undefined,
        playerB: round === 1 ? `Player ${index * 2 + 2}` : undefined,
        extraMinutes: 0,
      });
    });
  }

  return matches;
}

const LIVE_EVENT: Tournament = {
  id: 3,
  name: 'FIFA Cup #32',
  game: 'FIFA 25',
  cadence: 'WEEKLY',
  scheduledAt: '2026-09-03T13:00:00Z',
  entryFee: 200,
  prizePool: 1200,
  maxPlayers: 4,
  matchDurationMin: 20,
  status: 'LIVE',
  entries: 4,
  slotsLeft: 0,
};

const OPEN_EVENT: Tournament = {
  id: 4,
  name: 'Tekken Thursday',
  game: 'Tekken 8',
  cadence: 'WEEKLY',
  scheduledAt: '2026-09-05T13:00:00Z',
  entryFee: 150,
  prizePool: 600,
  maxPlayers: 8,
  matchDurationMin: 15,
  status: 'OPEN',
  entries: 3,
  slotsLeft: 5,
};

const ENTRIES: TournamentEntry[] = [1, 2, 3, 4].map((seed) => ({
  id: seed,
  tournamentId: 3,
  playerName: `Player ${seed}`,
  seed,
  checkedIn: false,
  refunded: false,
}));

const CONSOLES: ConsoleAvailability[] = [
  { stationId: 1, stationName: 'Nexus', available: true, state: 'FREE', note: 'Free' },
  { stationId: 2, stationName: 'Vortex', available: true, state: 'FREE', note: 'Free' },
];

const BUSY_CONSOLES: ConsoleAvailability[] = [
  {
    stationId: 1,
    stationName: 'Nexus',
    available: false,
    state: 'WALK_IN_SESSION',
    note: 'Allocated console busy with a walk-in session',
  },
];

const FINANCE: TournamentFinance = {
  entries: 4,
  entryFee: 200,
  prizePool: 1200,
  revenue: 800,
  netProfit: -400,
  matches: 3,
  matchDurationMin: 20,
  avgHourlyRate: 300,
  allocatedStations: 2,
  opportunityCost: 300,
  extraMargin: -700,
  verdict: 'This tournament earns ৳700 less than standard hourly rentals would.',
};

/** A four-player bracket with the first semi decided and its winner advanced. */
function decidedBracket(): TournamentMatch[] {
  const matches = perfectBracket(4);
  const semi = matches[0];
  semi.winnerEntryId = 1;
  semi.startedAt = '2026-09-03T11:30:00Z';
  semi.stationName = 'Nexus';
  semi.stationId = 1;
  // The server resolves propagation: the final already knows who arrived.
  const final = matches[2];
  final.entryA = 1;
  final.playerA = 'Player 1';
  return matches;
}

/** The second semi, live on a console with five minutes left. */
function liveBracket(remaining = 300): TournamentMatch[] {
  const matches = decidedBracket();
  const semi = matches[1];
  semi.startedAt = '2026-09-03T11:55:00Z';
  semi.stationId = 2;
  semi.stationName = 'Vortex';
  semi.remainingSeconds = remaining;
  semi.timeUp = remaining <= 0;
  return matches;
}

/* --------------------------------------------------------------- server */

const fetchMock = vi.fn();

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', Date: new Date(NOW).toUTCString() },
  });
}

function conflict(code: string, message: string, status = 409) {
  return json({ error: { code, message, traceId: 't-1' } }, status);
}

type Handlers = {
  list?: () => Response;
  detail?: () => Response;
  board?: () => Response;
  finance?: () => Response;
  history?: () => Response;
  winner?: () => Response;
  start?: () => Response;
  extend?: () => Response;
};

const calls: { method: string; path: string; body: Record<string, unknown> }[] = [];

/** What `GET /tournaments/{id}` answers next — mutated to model a server move. */
let detailNow: TournamentDetail;

function serve(handlers: Handlers = {}) {
  fetchMock.mockImplementation((input: RequestInfo, init?: RequestInit) => {
    const url = new URL(String(input));
    const path = url.pathname.replace('/api/v1', '');
    const method = (init?.method ?? 'GET').toUpperCase();
    const body = init?.body ? (JSON.parse(String(init.body)) as Record<string, unknown>) : {};
    calls.push({ method, path, body });

    if (method === 'GET' && path === '/stations') return json(STATIONS);
    if (method === 'GET' && path === '/tournaments') {
      return handlers.list?.() ?? json([LIVE_EVENT, OPEN_EVENT]);
    }
    if (method === 'GET' && path === '/tournaments/history') {
      return handlers.history?.() ?? json([{ ...LIVE_EVENT, id: 1, status: 'DONE', winnerName: 'Player 1' }]);
    }
    if (method === 'GET' && path === '/tournaments/3') {
      return handlers.detail?.() ?? json(detailNow);
    }
    if (method === 'GET' && path === '/tournaments/3/matches') {
      return handlers.board?.() ?? json({ consoles: CONSOLES, freeConsoles: 2, matches: detailNow.bracket });
    }
    if (method === 'GET' && path === '/tournaments/3/finance') {
      return handlers.finance?.() ?? json(FINANCE);
    }
    if (method === 'GET' && path === '/tournaments/4') {
      return json({
        tournament: OPEN_EVENT,
        entries: ENTRIES.slice(0, 3).map((entry) => ({ ...entry, tournamentId: 4 })),
        stationIds: [],
        bracket: [],
      } satisfies TournamentDetail);
    }
    if (method === 'GET' && path === '/tournaments/4/matches') {
      return json({ consoles: [], freeConsoles: 0, matches: [] });
    }
    if (method === 'GET' && path === '/tournaments/4/finance') return json(FINANCE);

    if (method === 'POST' && /\/winner$/.test(path)) {
      if (handlers.winner) return handlers.winner();
      const decided = detailNow.bracket?.map((match) =>
        match.id === 2 ? { ...match, winnerEntryId: 3, playerB: 'Player 3' } : match,
      );
      detailNow = { ...detailNow, bracket: decided };
      return json({
        tournament: detailNow.tournament,
        entries: detailNow.entries,
        stationIds: detailNow.stationIds,
        bracket: decided,
        champion: false,
      });
    }
    if (method === 'POST' && /\/start$/.test(path)) {
      return handlers.start?.() ?? json({ id: 2, startedAt: NOW, stationName: 'Vortex' });
    }
    if (method === 'POST' && /\/extend$/.test(path)) {
      if (handlers.extend) return handlers.extend();
      // The server adds the minutes and every countdown re-bases off the read
      // that follows — which is the only place the extra time exists.
      detailNow = { ...detailNow, bracket: liveBracket(300 + 5 * 60).map(withExtra) };
      return json({ id: 2, extraMinutes: 5, remainingSeconds: 600 });
    }

    return json({});
  });
}

const withExtra = (match: TournamentMatch): TournamentMatch =>
  match.id === 2 ? { ...match, extraMinutes: 5 } : match;

let client: QueryClient;

function renderScreen(role: 'ADMIN' | 'MANAGER' | 'CASHIER' = 'MANAGER') {
  client = makeQueryClient();
  client.setDefaultOptions({ queries: { retry: false } });
  return render(
    <QueryClientProvider client={client}>
      <TournamentsScreen role={role} />
    </QueryClientProvider>,
  );
}

async function openScreen(role: 'ADMIN' | 'MANAGER' | 'CASHIER' = 'MANAGER') {
  const user = userEvent.setup();
  renderScreen(role);
  await waitFor(() => expect(screen.getByTestId('tournament-heading')).toBeInTheDocument());
  return user;
}

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock);
  fetchMock.mockReset();
  calls.length = 0;
  resetPosStore();
  forgetSession();
  resetServerTime();
  resetIdempotencyKeys();
  detailNow = {
    tournament: LIVE_EVENT,
    entries: ENTRIES,
    stationIds: [1, 2],
    bracket: liveBracket(),
  };
  serve();
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

const financeCalls = () => calls.filter((call) => call.path.endsWith('/finance'));

/**
 * The clocks tick for real off the server offset, and the render happens a
 * few milliseconds after the reading landed — so `5:00` paints as `5:00` or
 * `4:59`. Both are the same reading; anything else is a bug.
 */
function clockReadings(seconds: number): string[] {
  return [formatCountdown(seconds), formatCountdown(seconds - 1)];
}

function expectClock(element: HTMLElement, seconds: number) {
  expect(clockReadings(seconds)).toContain(element.textContent?.trim());
}

/** True when some bracket tag reads `«console» · mm:ss` for this reading. */
function hasTag(console: string, seconds: number): boolean {
  const wanted = clockReadings(seconds).map((reading) => `${console} · ${reading}`);
  return screen
    .getAllByTestId('match-tag')
    .some((tag) => wanted.includes(tag.textContent?.trim() ?? ''));
}

/* ----------------------------------------------------------------- pure */

describe('bracket shapes', () => {
  it('is N−1 matches and no byes for every cap the API allows', () => {
    for (const cap of [4, 8, 16, 32]) {
      expect(expectedMatchCount(cap)).toBe(cap - 1);
      expect(perfectBracket(cap)).toHaveLength(cap - 1);
    }
  });

  it('names the columns backwards from the final', () => {
    expect(roundLabels(1)).toEqual(['Final']);
    expect(roundLabels(2)).toEqual(['Semi-finals', 'Final']);
    expect(roundLabels(3)).toEqual(['Quarter-finals', 'Semi-finals', 'Final']);
    expect(roundLabels(4)).toEqual([
      'Round of 16',
      'Quarter-finals',
      'Semi-finals',
      'Final',
    ]);
    expect(roundLabels(5)[0]).toBe('Round of 32');
  });

  it('groups the server’s flat match list into rounds in slot order', () => {
    const rounds = bracketRounds(perfectBracket(8).slice().reverse());
    expect(rounds.map((round) => round.label)).toEqual([
      'Quarter-finals',
      'Semi-finals',
      'Final',
    ]);
    expect(rounds[0].matches.map((match) => match.slot)).toEqual([1, 2, 3, 4]);
    expect(rounds.reduce((total, round) => total + round.matches.length, 0)).toBe(7);
  });

  it('lets any role decide a started match, and only a manager an un-started one', () => {
    const [started, ready] = [liveBracket()[1], perfectBracket(4)[1]];
    expect(canDecide(started, false)).toBe(true);
    expect(canDecide(ready, false)).toBe(false);
    expect(canDecide(ready, true)).toBe(true);
    // A decided match is nobody's to decide again.
    expect(canDecide({ ...started, winnerEntryId: 3 }, true)).toBe(false);
  });

  it('tags a match with its console and clock, or with “time up”', () => {
    const match = liveBracket()[1];
    expect(matchTag(match, 305)).toBe('Vortex · 5:05');
    expect(matchTag(match, 0)).toBe('Vortex · time up');
    expect(matchTag(perfectBracket(4)[1], null, true)).toBe('Up next');
    expect(matchTag(perfectBracket(4)[1], null, false)).toBeNull();
  });

  it('says what the board is waiting on, in the server’s own words', () => {
    expect(consoleHint(CONSOLES)).toBe('Next free console: Nexus');
    expect(consoleHint(BUSY_CONSOLES)).toBe('Allocated console busy with a walk-in session');
    expect(consoleHint([])).toMatch(/No consoles are blocked/);

    const live = liveBracket()[1];
    expect(boardStatus(live, CONSOLES, 305)).toBe('On Vortex · 5:05 left');
    expect(boardStatus({ ...live, extraMinutes: 5 }, CONSOLES, 305)).toBe(
      'On Vortex · 5:05 left · +5 min added',
    );
    expect(boardStatus(live, CONSOLES, 0)).toBe('On Vortex · time up — record the winner');
    expect(boardStatus(perfectBracket(4)[1], BUSY_CONSOLES, null)).toBe(
      'Allocated console busy with a walk-in session',
    );
  });

  it('reads the event’s status and slots the way the cards print them', () => {
    expect(tournamentStatusLabel('LIVE')).toBe('Live');
    expect(tournamentStatusLabel('OPEN')).toBe('Registration');
    expect(tournamentStatusLabel('CANCELLED')).toBe('Called off');
    expect(tournamentStatusTag('LIVE')).toBe('accent');
    expect(tournamentStatusTag('OPEN')).toBe('outline');
    expect(tournamentStatusTag('DONE')).toBe('neutral');
    expect(slotsNote(OPEN_EVENT)).toMatch(/5 of 8 slots open/);
  });

  it('spells out where the opportunity cost came from, and both verdicts', () => {
    expect(financeFormula(FINANCE)).toBe(
      'Opportunity cost = 3 matches × 20 min × ৳300/hr standard rate',
    );
    expect(financeVerdict(FINANCE)).toMatch(/less than standard hourly rentals/);
    expect(financeVerdict({ ...FINANCE, verdict: undefined, extraMargin: 2500 })).toBe(
      'This tournament generates ৳2,500 extra compared to standard hourly rentals.',
    );
  });

  it('knows which roles configure a tournament', () => {
    expect(canManageTournaments('ADMIN')).toBe(true);
    expect(canManageTournaments('MANAGER')).toBe(true);
    expect(canManageTournaments('CASHIER')).toBe(false);
    expect(canManageTournaments(null)).toBe(false);
  });
});

/* --------------------------------------------------------------- render */

describe('the bracket on screen', () => {
  it.each([4, 8, 16, 32])('draws exactly N−1 boxes for a cap of %i', async (cap) => {
    detailNow = {
      tournament: { ...LIVE_EVENT, maxPlayers: cap, entries: cap, slotsLeft: 0 },
      entries: ENTRIES,
      stationIds: [1, 2],
      bracket: perfectBracket(cap),
    };
    serve();
    await openScreen();

    await waitFor(() => expect(screen.getByTestId('bracket')).toBeInTheDocument());
    expect(screen.getAllByTestId('match-box')).toHaveLength(cap - 1);
    expect(screen.getAllByTestId('bracket-round')).toHaveLength(Math.log2(cap));
    expect(screen.getByText('Final')).toBeInTheDocument();
  });

  it('shows propagation: the winner marked, the loser dimmed, the next box filled', async () => {
    await openScreen();
    await waitFor(() => expect(screen.getByTestId('bracket')).toBeInTheDocument());

    const boxes = screen.getAllByTestId('match-box');
    const decided = boxes[0];
    expect(within(decided).getByTestId('match-winner-mark')).toHaveTextContent('W');

    const rows = within(decided).getAllByTestId('match-row');
    expect(rows[0]).toHaveTextContent('Player 1');
    expect(rows[1]).toHaveTextContent('Player 2');
    // The loser dims; the winner is the one carrying the mark.
    expect(rows[1].className).toContain('opacity-40');

    // The final knows who arrived from the decided semi, and still waits on the
    // other one — which is propagation, displayed rather than re-simulated.
    const final = boxes[2];
    expect(within(final).getAllByTestId('match-row')[0]).toHaveTextContent('Player 1');
    expect(within(final).getAllByTestId('match-row')[1]).toHaveTextContent('TBD');
  });

  it('ticks the live match off the server clock and offers “Winner ✓” on it', async () => {
    await openScreen();
    await waitFor(() => expect(screen.getByTestId('live-match-tile')).toBeInTheDocument());

    expectClock(screen.getByTestId('live-match-time'), 300);
    expect(hasTag('Vortex', 300)).toBe(true);
    expect(screen.getAllByRole('button', { name: /Record Player 3 as the winner/ })).toHaveLength(1);
  });

  it('turns the tile accent and says TIME UP once the clock is out', async () => {
    detailNow = { ...detailNow, bracket: liveBracket(0) };
    serve();
    await openScreen();

    await waitFor(() => expect(screen.getByTestId('live-match-tile')).toBeInTheDocument());
    expect(screen.getByTestId('live-match-tile')).toHaveAttribute('data-state', 'time-up');
    expect(screen.getByTestId('live-match-time')).toHaveTextContent('TIME UP');
    expect(screen.getByTestId('match-board-status')).toHaveTextContent(
      'time up — record the winner',
    );
  });

  it('shows the champion banner instead of a live tile once the final is in', async () => {
    const bracket = decidedBracket();
    bracket[1].winnerEntryId = 3;
    bracket[2].entryB = 3;
    bracket[2].playerB = 'Player 3';
    bracket[2].winnerEntryId = 1;
    detailNow = {
      tournament: { ...LIVE_EVENT, status: 'DONE', winnerName: 'Player 1' },
      entries: ENTRIES,
      stationIds: [],
      bracket,
    };
    serve();
    await openScreen();

    await waitFor(() => expect(screen.getByTestId('champion-banner')).toBeInTheDocument());
    expect(screen.getByTestId('champion-banner')).toHaveTextContent('Player 1');
    expect(screen.getByTestId('champion-banner')).toHaveTextContent('৳1,200');
    expect(screen.queryByTestId('live-match-tile')).not.toBeInTheDocument();
    // A finished event has no board to work.
    expect(screen.queryByTestId('match-board')).not.toBeInTheDocument();
  });
});

/* ------------------------------------------------------------ the board */

describe('the match board', () => {
  it('re-bases every countdown when +5 min is added', async () => {
    const user = await openScreen();
    await waitFor(() => expect(screen.getByTestId('live-match-tile')).toBeInTheDocument());
    expectClock(screen.getByTestId('live-match-time'), 300);

    await user.click(screen.getByRole('button', { name: '+5 min' }));

    // The minutes exist on the server, and the next read is what moves the
    // clocks — tile, bracket tag and board row together.
    await waitFor(() =>
      expect(clockReadings(600)).toContain(
        screen.getByTestId('live-match-time').textContent?.trim(),
      ),
    );
    expect(hasTag('Vortex', 600)).toBe(true);
    expect(screen.getByTestId('match-board-status')).toHaveTextContent('+5 min added');
    expect(calls.some((call) => call.method === 'POST' && call.path.endsWith('/extend'))).toBe(true);
    expect(calls.find((call) => call.path.endsWith('/extend'))?.body).toEqual({ minutes: 5 });
  });

  it('names the console a start would take, and refuses when none is free', async () => {
    await openScreen();
    await waitFor(() => expect(screen.getByTestId('match-board')).toBeInTheDocument());
    // The live semi is the only pending row here, so it reads as in play.
    expect(screen.getByTestId('start-match')).toBeDisabled();
    expect(screen.getByTestId('start-match')).toHaveTextContent('In play');
  });

  it('explains a dead start button with the server’s own note', async () => {
    detailNow = { ...detailNow, bracket: decidedBracket() };
    serve({ board: () => json({ consoles: BUSY_CONSOLES, freeConsoles: 0, matches: [] }) });
    await openScreen();

    await waitFor(() => expect(screen.getByTestId('match-board')).toBeInTheDocument());
    expect(screen.getByTestId('match-board-status')).toHaveTextContent(
      'Allocated console busy with a walk-in session',
    );
    expect(screen.getByTestId('start-match')).toBeDisabled();
  });

  it('keeps the board intact when a start is refused', async () => {
    detailNow = { ...detailNow, bracket: decidedBracket() };
    serve({ start: () => conflict('NO_FREE_CONSOLE', 'Every allocated console is taken.') });
    const user = await openScreen();

    await waitFor(() => expect(screen.getByTestId('start-match')).toBeEnabled());
    await user.click(screen.getByTestId('start-match'));

    await waitFor(() =>
      expect(screen.getByTestId('match-board-notice')).toHaveTextContent(
        'No free console of that type right now.',
      ),
    );
    expect(screen.getByTestId('match-board-row')).toHaveAttribute('data-started', 'false');
  });
});

/* ----------------------------------------------------------- the winner */

describe('recording a winner', () => {
  it('writes nothing until the server answers, then redraws from its bracket', async () => {
    const user = await openScreen();
    await waitFor(() => expect(screen.getByTestId('bracket')).toBeInTheDocument());

    const before = screen.getAllByTestId('match-winner-mark').length;
    await user.click(screen.getByRole('button', { name: /Record Player 3 as the winner/ }));

    await waitFor(() =>
      expect(screen.getAllByTestId('match-winner-mark').length).toBe(before + 1),
    );
    expect(calls.find((call) => call.path.endsWith('/winner'))?.body).toEqual({ winnerEntryId: 3 });
  });

  it('raises the failure banner and leaves the bracket alone when it is refused', async () => {
    serve({ winner: () => conflict('CONFLICT', 'That match has already been decided.') });
    const user = await openScreen();
    await waitFor(() => expect(screen.getByTestId('bracket')).toBeInTheDocument());

    const before = screen.getAllByTestId('match-winner-mark').length;
    await user.click(screen.getByRole('button', { name: /Record Player 3 as the winner/ }));

    await waitFor(() => expect(screen.getByTestId('winner-error')).toBeInTheDocument());
    expect(screen.getByTestId('winner-error')).toHaveTextContent(
      'That match has already been decided.',
    );
    expect(screen.getAllByTestId('match-winner-mark')).toHaveLength(before);
  });
});

/* ------------------------------------------------------------ the rails */

describe('the role rails', () => {
  it('gives a cashier guidance and the POS deep-link, and no manager rail', async () => {
    await openScreen('CASHIER');

    expect(screen.getByTestId('tournament-cashier-rail')).toBeInTheDocument();
    expect(screen.queryByTestId('tournament-manager-rail')).not.toBeInTheDocument();
    expect(screen.queryByTestId('arrange-form')).not.toBeInTheDocument();
    expect(screen.queryByTestId('cancel-tournament')).not.toBeInTheDocument();
    expect(screen.queryByTestId('finance-panel')).not.toBeInTheDocument();
    expect(screen.getByTestId('sell-entry-at-pos')).toBeInTheDocument();
  });

  it('never mounts the finance query for a cashier', async () => {
    await openScreen('CASHIER');
    // Let anything the screen was going to ask for actually go out.
    await waitFor(() => expect(calls.some((call) => call.path === '/tournaments/3')).toBe(true));

    expect(financeCalls()).toHaveLength(0);
    expect(
      client.getQueryState(['tournaments', 3, 'finance'] as const)?.fetchStatus ?? 'idle',
    ).toBe('idle');
  });

  it('mounts it for a manager, with the four stats and the verdict', async () => {
    await openScreen('MANAGER');

    await waitFor(() => expect(screen.getByTestId('finance-verdict')).toBeInTheDocument());
    expect(financeCalls().length).toBeGreaterThan(0);
    expect(screen.getByTestId('finance-panel')).toHaveTextContent('৳800');
    expect(screen.getByTestId('finance-extra')).toHaveTextContent('−৳700');
    expect(screen.getByTestId('finance-verdict')).toHaveTextContent(
      'less than standard hourly rentals',
    );
  });

  it('renders the manager’s controls: block chips, cancel and the arrange form', async () => {
    await openScreen('ADMIN');

    expect(screen.getByTestId('tournament-manager-rail')).toBeInTheDocument();
    expect(screen.getByTestId('cancel-tournament')).toBeEnabled();
    expect(screen.getByTestId('arrange-form')).toBeInTheDocument();
    // Powers of two only — the chips are the cap rule made visible (§3).
    const caps = within(screen.getByRole('group', { name: 'Player cap' })).getAllByRole('button');
    expect(caps.map((chip) => chip.textContent)).toEqual(['4', '8', '16', '32']);
    // The consoles this event holds come back pressed, once the detail lands.
    await waitFor(() => {
      const blocked = within(
        screen.getByRole('group', { name: 'Blocked stations' }),
      ).getAllByRole('button');
      expect(blocked).toHaveLength(2);
      expect(blocked.every((chip) => chip.getAttribute('aria-pressed') === 'true')).toBe(true);
    });
  });
});

/* ------------------------------------------------------------ the screen */

describe('the screen’s states', () => {
  it('says so when nothing is scheduled', async () => {
    serve({ list: () => json([]) });
    renderScreen('CASHIER');

    await waitFor(() => expect(screen.getByTestId('tournaments-empty')).toBeInTheDocument());
    expect(screen.getByTestId('tournaments-empty')).toHaveTextContent('No tournaments scheduled');
    expect(screen.queryByTestId('bracket')).not.toBeInTheDocument();
  });

  it('shows the registered players and the slots note before the draw', async () => {
    const user = await openScreen('MANAGER');
    await user.click(screen.getByText('Tekken Thursday'));

    await waitFor(() => expect(screen.getByTestId('registered-players')).toBeInTheDocument());
    expect(screen.getAllByTestId('registered-player')).toHaveLength(3);
    expect(screen.getByTestId('registered-players')).toHaveTextContent('5 of 8 slots open');
    expect(screen.queryByTestId('bracket')).not.toBeInTheDocument();
    // Three entries is enough to draw an undersubscribed bracket by hand (§3).
    expect(screen.getByTestId('generate-bracket')).toBeEnabled();
  });

  it('hides the draw button from a cashier', async () => {
    const user = await openScreen('CASHIER');
    await user.click(screen.getByText('Tekken Thursday'));

    await waitFor(() => expect(screen.getByTestId('registered-players')).toBeInTheDocument());
    expect(screen.queryByTestId('generate-bracket')).not.toBeInTheDocument();
  });

  it('switches to the History tab and reads the finished events', async () => {
    const user = await openScreen('MANAGER');
    await user.click(screen.getByRole('radio', { name: 'History' }));

    await waitFor(() =>
      expect(calls.some((call) => call.path === '/tournaments/history')).toBe(true),
    );
    expect(screen.getByRole('table')).toHaveTextContent('Player 1');
    expect(screen.queryByTestId('bracket')).not.toBeInTheDocument();
  });

  it('shows the read error without destroying the screen', async () => {
    serve({ list: () => conflict('SYNC_UNAVAILABLE', 'The cloud is unreachable.', 503) });
    renderScreen('MANAGER');

    await waitFor(() => expect(screen.getByTestId('tournaments-error')).toBeInTheDocument());
  });
});
