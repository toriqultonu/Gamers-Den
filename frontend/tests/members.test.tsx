/**
 * S6 + S6a — Members: the directory, the rail, the two wallet writes and the
 * register-and-seat flow (design.md §1, api-contract.md "Members, wallet,
 * points").
 *
 * State-table assertions, not snapshots. What is pinned down here is the
 * handful of places this screen is allowed to talk to the server, and what it
 * does with each refusal:
 *
 *  - the search box asks **once per pause**, not once per keystroke;
 *  - `DUPLICATE_PHONE` is an inline error under the phone field and the typed
 *    form survives it — that customer is already on file;
 *  - a redemption refused with `INSUFFICIENT_POINTS` leaves the wallet reading
 *    exactly where it was: neither wallet write is optimistic;
 *  - "Save & seat" registers, tops up and starts the session in that order,
 *    and the seat invalidates both `['sessions']` and `['stations']` before
 *    the Floor is handed the terminal.
 */

import { act, render, renderHook, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MembersScreen, SEARCH_DEBOUNCE_MS, memberColumns } from '@/components/domain/members-screen';
import { firstFreeStation } from '@/components/domain/new-member-dialog';
import { useDebouncedValue } from '@/lib/use-debounced-value';
import {
  createMemberSchema,
  fieldError,
  isMfs,
  maxRedeemablePoints,
  memberSince,
  playsSummary,
  redeemPointsSchema,
  topupSchema,
} from '@/features/members/schemas';
import { memberRows } from '@/features/members/queries';
import { makeQueryClient } from '@/lib/query-client';
import { forgetSession } from '@/lib/api';
import { resetPosStore, useAppStore } from '@/features/pos/bill-store';
import type { Member, MemberDetail } from '@/features/members/schemas';
import type { Station } from '@/features/sessions/queries';

const { push } = vi.hoisted(() => ({ push: vi.fn() }));

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push, replace: vi.fn(), prefetch: vi.fn(), refresh: vi.fn() }),
  usePathname: () => '/members',
  useSearchParams: () => new URLSearchParams(),
}));

const NOW = '2026-09-03T12:00:00Z';

/* ------------------------------------------------------------- fixtures */

const RIFAT: Member = {
  id: 7,
  name: 'Rifat Hasan',
  phone: '+8801711000111',
  wallet: 1200,
  points: 340,
  preferredConsole: 'PS5',
  games: ['FIFA 25'],
  createdAt: '2025-01-12T10:00:00Z',
};

const TANVIR: Member = {
  id: 8,
  name: 'Tanvir Ahmed',
  phone: '+8801812000222',
  wallet: 0,
  points: 0,
  preferredConsole: 'PS4',
  games: [],
  createdAt: '2026-09-03T09:00:00Z',
};

const RIFAT_DETAIL: MemberDetail = {
  ...RIFAT,
  visits: [
    {
      sessionId: 41,
      stationId: 1,
      stationName: 'Nexus',
      consoleType: 'PS5',
      blocks: 4,
      playedSeconds: 7200,
      startedAt: '2026-09-01T14:00:00Z',
      endedAt: '2026-09-01T16:00:00Z',
      state: 'CLOSED',
    },
  ],
  bookings: [
    {
      bookingId: 90,
      stationId: 1,
      stationName: 'Nexus',
      startAt: '2026-09-04T15:00:00Z',
      blocks: 4,
      status: 'PAID',
      total: 1000,
      tokenNo: 12,
    },
  ],
};

const STATIONS: Station[] = [
  { id: 1, name: 'Nexus', consoleType: 'PS5', floorState: 'RUNNING', status: 'AVAILABLE' },
  { id: 2, name: 'Vortex', consoleType: 'PS5', floorState: 'FREE', status: 'AVAILABLE' },
];

/* --------------------------------------------------------------- server */

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

type Handlers = {
  directory?: (q: string) => Response;
  createMember?: (body: Record<string, unknown>) => Response;
  topup?: (body: Record<string, unknown>) => Response;
  redeem?: (body: Record<string, unknown>) => Response;
  startSession?: (body: Record<string, unknown>) => Response;
};

/** Every request the screen made, in order. */
const calls: { method: string; path: string; query: URLSearchParams; body: Record<string, unknown>; key: string | null }[] = [];

function serve(handlers: Handlers = {}) {
  fetchMock.mockImplementation((input: RequestInfo, init?: RequestInit) => {
    const url = new URL(String(input));
    const path = url.pathname.replace('/api/v1', '');
    const method = (init?.method ?? 'GET').toUpperCase();
    const body = init?.body ? (JSON.parse(String(init.body)) as Record<string, unknown>) : {};
    const key = new Headers(init?.headers).get('Idempotency-Key');
    calls.push({ method, path, query: url.searchParams, body, key });

    if (method === 'GET' && path === '/stations') return json(STATIONS);

    if (method === 'GET' && path === '/members') {
      const q = url.searchParams.get('q') ?? '';
      if (handlers.directory) return handlers.directory(q);
      const digits = q.replace(/\D/g, '');
      const hits = [RIFAT, TANVIR].filter(
        (member) =>
          q === '' ||
          (member.name ?? '').toLowerCase().includes(q.toLowerCase()) ||
          (digits !== '' && (member.phone ?? '').replace(/\D/g, '').includes(digits)),
      );
      return json({ content: hits, page: 0, size: 50, totalElements: hits.length });
    }

    if (method === 'GET' && path === '/members/7') return json(RIFAT_DETAIL);
    if (method === 'GET' && path === '/members/8') return json({ ...TANVIR, visits: [], bookings: [] });

    if (method === 'POST' && path === '/members') {
      return handlers.createMember?.(body) ?? json({ ...TANVIR, id: 12, ...body }, 201);
    }
    if (method === 'POST' && path === '/members/7/wallet/topup') {
      return (
        handlers.topup?.(body) ??
        json({ ...RIFAT, wallet: (RIFAT.wallet ?? 0) + Number(body.amount ?? 0) })
      );
    }
    if (method === 'POST' && path === '/members/7/wallet/redeem-points') {
      const points = Number(body.points ?? 0);
      return (
        handlers.redeem?.(body) ??
        json({ ...RIFAT, wallet: (RIFAT.wallet ?? 0) + points, points: (RIFAT.points ?? 0) - points })
      );
    }
    if (method === 'POST' && path === '/members/12/wallet/topup') {
      return handlers.topup?.(body) ?? json({ ...TANVIR, id: 12, wallet: Number(body.amount ?? 0) });
    }
    if (method === 'POST' && path === '/sessions') {
      return handlers.startSession?.(body) ?? json({ id: 55, stationId: body.stationId, state: 'OPEN' });
    }

    return json({});
  });
}

let client: QueryClient;

function renderMembers() {
  client = makeQueryClient();
  client.setDefaultOptions({ queries: { retry: false } });
  return render(
    <QueryClientProvider client={client}>
      <MembersScreen />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock);
  fetchMock.mockReset();
  calls.length = 0;
  push.mockReset();
  resetPosStore();
  forgetSession();
  serve();
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

/** Run the fake clock forward and let the queries that fall out of it finish. */
async function settle(ms = 0) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0);
  });
}

const directoryCalls = () =>
  calls.filter((call) => call.method === 'GET' && call.path === '/members').map((call) => call.query.get('q') ?? '');

/* ----------------------------------------------------------------- pure */

describe('member shapes', () => {
  it('registers on a name and a phone, and nothing else is required', () => {
    expect(createMemberSchema.safeParse({ name: 'Rifat', phone: '01711000111' }).success).toBe(true);
    const blank = createMemberSchema.safeParse({ name: '  ', phone: '01711000111' });
    expect(blank.success).toBe(false);
    expect(fieldError(blank.success ? null : blank.error, 'name')).toMatch(/name/i);
  });

  it('a top-up is a whole number of taka, at least one, and never funded by a wallet', () => {
    expect(topupSchema.safeParse({ amount: 500, method: 'CASH' }).success).toBe(true);
    expect(topupSchema.safeParse({ amount: 0, method: 'CASH' }).success).toBe(false);
    expect(topupSchema.safeParse({ amount: 500, method: 'WALLET' }).success).toBe(false);
    expect(isMfs('BKASH')).toBe(true);
    expect(isMfs('CASH')).toBe(false);
  });

  it('a redemption is capped at the points the member holds, not at a bill', () => {
    expect(maxRedeemablePoints({ points: 340 })).toBe(340);
    expect(maxRedeemablePoints(undefined)).toBe(0);
    expect(redeemPointsSchema.safeParse({ points: 0 }).success).toBe(false);
  });

  it('dates the member from the venue day and lists what they play', () => {
    expect(memberSince('2026-09-03T09:00:00Z', '2026-09-03')).toBe('Registered today');
    expect(memberSince('2025-01-12T10:00:00Z', '2026-09-03')).toMatch(/^Member since /);
    expect(playsSummary({ preferredConsole: 'PS5', games: ['FIFA 25'] })).toBe('PS5 · FIFA 25');
    expect(playsSummary({})).toBe('—');
  });

  it('only a genuinely free console is offered for seating — BOOKED is somebody else’s seat', () => {
    expect(firstFreeStation(STATIONS)?.id).toBe(2);
    expect(firstFreeStation([{ id: 3, floorState: 'BOOKED' }] as Station[])).toBeNull();
    expect(firstFreeStation(undefined)).toBeNull();
  });

  it('the table shows what a directory row knows — the history is the rail’s', () => {
    expect(memberColumns('2026-09-03').map((column) => column.key)).toEqual([
      'name',
      'phone',
      'plays',
      'wallet',
      'points',
      'since',
    ]);
    expect(memberRows(undefined)).toEqual([]);
  });
});

describe('useDebouncedValue', () => {
  it('settles once the value stops changing', () => {
    vi.useFakeTimers();
    const { result, rerender } = renderHook(({ value }) => useDebouncedValue(value, 250), {
      initialProps: { value: 'r' },
    });

    rerender({ value: 'ri' });
    rerender({ value: 'rif' });
    expect(result.current).toBe('r');

    act(() => {
      vi.advanceTimersByTime(249);
    });
    expect(result.current).toBe('r');

    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(result.current).toBe('rif');
  });
});

/* ------------------------------------------------------------ the screen */

describe('S6 — the directory', () => {
  it('opens on the whole directory and searches once per pause, not once per keystroke', async () => {
    // `shouldAdvanceTime` keeps user-event's own waits alive while the debounce
    // is driven by hand — the repo's pattern in tests/app-shell.test.tsx.
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const user = userEvent.setup({ advanceTimers: (ms) => vi.advanceTimersByTime(ms) });
    renderMembers();

    await settle();
    expect(screen.getByText('Rifat Hasan')).toBeInTheDocument();
    expect(directoryCalls()).toEqual(['']);

    await user.type(screen.getByTestId('member-search-input'), 'rifat');

    // Five keystrokes, no requests: the box is responsive, the cache is not asked.
    expect(directoryCalls()).toEqual(['']);

    await settle(SEARCH_DEBOUNCE_MS + 20);

    expect(directoryCalls()).toEqual(['', 'rifat']);
    expect(screen.queryByText('Tanvir Ahmed')).not.toBeInTheDocument();
  });

  it('a search with no match says so and points at registration', async () => {
    const user = userEvent.setup();
    renderMembers();
    await waitFor(() => expect(screen.getByText('Rifat Hasan')).toBeInTheDocument());

    await user.type(screen.getByTestId('member-search-input'), 'zzz');

    await waitFor(() => expect(screen.getByTestId('members-empty')).toBeInTheDocument());
    expect(screen.getByTestId('members-empty')).toHaveTextContent(/New member/);
  });

  it('a failed directory read is a banner, not a blank screen', async () => {
    serve({ directory: () => json({ error: { code: 'SYNC_UNAVAILABLE', message: 'down' } }, 503) });
    renderMembers();
    await waitFor(() => expect(screen.getByTestId('members-error')).toBeInTheDocument());
  });

  it('a 403 renders the access notice', async () => {
    serve({ directory: () => json({ error: { code: 'FORBIDDEN', message: 'no' } }, 403) });
    renderMembers();
    await waitFor(() => expect(screen.getByTestId('access-notice')).toBeInTheDocument());
  });

  it('picking a row opens the member with their wallet, visits and bookings', async () => {
    const user = userEvent.setup();
    renderMembers();
    await waitFor(() => expect(screen.getByText('Rifat Hasan')).toBeInTheDocument());
    expect(screen.getByTestId('member-rail-idle')).toBeInTheDocument();

    await user.click(screen.getByText('Rifat Hasan'));

    await waitFor(() => expect(screen.getByTestId('member-rail')).toBeInTheDocument());
    expect(screen.getByTestId('member-wallet')).toHaveTextContent('৳1,200');
    const rail = screen.getByTestId('member-rail');
    expect(within(rail).getAllByText(/Nexus/)).toHaveLength(2);
    expect(within(rail).getByText('#12')).toBeInTheDocument();
    expect(within(rail).getByText('PAID')).toBeInTheDocument();
    expect(within(rail).queryByTestId('member-no-visits')).not.toBeInTheDocument();
  });
});

/* ------------------------------------------------------------ the wallet */

async function openMember() {
  const user = userEvent.setup();
  renderMembers();
  await waitFor(() => expect(screen.getByText('Rifat Hasan')).toBeInTheDocument());
  await user.click(screen.getByText('Rifat Hasan'));
  await waitFor(() => expect(screen.getByTestId('member-rail')).toBeInTheDocument());
  return user;
}

describe('S6 — wallet writes', () => {
  it('a top-up carries an Idempotency-Key and only moves the wallet when the server says so', async () => {
    const user = await openMember();

    await user.click(screen.getByRole('button', { name: 'Top up' }));
    await user.click(within(screen.getByTestId('topup-dialog')).getByRole('button', { name: '৳1,000' }));
    await user.click(screen.getByRole('button', { name: /Add ৳1,000/ }));

    await waitFor(() => expect(screen.queryByTestId('topup-dialog')).not.toBeInTheDocument());
    const topup = calls.find((call) => call.path === '/members/7/wallet/topup');
    expect(topup?.body).toMatchObject({ amount: 1000, method: 'CASH' });
    expect(topup?.key).toMatch(/[0-9a-f-]{36}/);
  });

  it('a refused top-up keeps the dialog, the typed amount and the wallet reading', async () => {
    serve({ topup: () => json({ error: { code: 'RATE_LIMITED', message: 'slow down' } }, 429) });
    const user = await openMember();

    await user.click(screen.getByRole('button', { name: 'Top up' }));
    await user.click(screen.getByRole('button', { name: /Add ৳500/ }));

    await waitFor(() => expect(screen.getByTestId('topup-notice')).toBeInTheDocument());
    expect(screen.getByTestId('topup-dialog')).toBeInTheDocument();
    expect(screen.getByLabelText('Amount (৳)')).toHaveValue('500');
    expect(screen.getByTestId('member-wallet')).toHaveTextContent('৳1,200');
  });

  it('a redemption refused with INSUFFICIENT_POINTS keeps the choice and never moves the wallet', async () => {
    serve({ redeem: () => conflict('INSUFFICIENT_POINTS', 'not enough') });
    const user = await openMember();

    await user.click(screen.getByTestId('open-redeem'));
    const dialog = screen.getByTestId('redeem-dialog');
    await user.click(within(dialog).getByRole('button', { name: '200' }));
    await user.click(screen.getByRole('button', { name: 'Redeem 200 pts' }));

    await waitFor(() => expect(screen.getByTestId('redeem-notice')).toBeInTheDocument());
    expect(screen.getByTestId('redeem-notice')).toHaveTextContent('Not enough points to redeem that much.');
    // The dialog is still open on the same choice, and the rail has not moved.
    expect(screen.getByRole('button', { name: 'Redeem 200 pts' })).toBeInTheDocument();
    expect(screen.getByTestId('member-wallet')).toHaveTextContent('৳1,200');
  });

  it('the stepper cannot offer more points than the member holds', async () => {
    const user = await openMember();
    await user.click(screen.getByTestId('open-redeem'));

    const stepper = screen.getByTestId('redeem-stepper');
    expect(stepper).toHaveAttribute('data-max', '340');
    expect(within(stepper).getByRole('button', { name: 'Max 340' })).toBeInTheDocument();
    expect(within(stepper).queryByRole('button', { name: '400' })).not.toBeInTheDocument();
  });

  it('Redeem is dead for a member with no points', async () => {
    const user = userEvent.setup();
    renderMembers();
    await waitFor(() => expect(screen.getByText('Tanvir Ahmed')).toBeInTheDocument());
    await user.click(screen.getByText('Tanvir Ahmed'));

    await waitFor(() => expect(screen.getByTestId('member-rail')).toBeInTheDocument());
    expect(screen.getByTestId('open-redeem')).toBeDisabled();
  });
});

/* ---------------------------------------------------------------- S6a */

async function openNewMember() {
  const user = userEvent.setup();
  renderMembers();
  await waitFor(() => expect(screen.getByText('Rifat Hasan')).toBeInTheDocument());
  await user.click(screen.getByRole('button', { name: 'New member' }));
  await waitFor(() => expect(screen.getByTestId('new-member')).toBeInTheDocument());
  return user;
}

describe('S6a — new member', () => {
  it('DUPLICATE_PHONE is an inline error under the phone field and the form survives it', async () => {
    serve({ createMember: () => conflict('DUPLICATE_PHONE', 'already on file') });
    const user = await openNewMember();

    await user.type(screen.getByLabelText('Full name'), 'Rifat Hasan');
    await user.type(screen.getByLabelText('Phone number'), '01711000111');
    await user.click(screen.getByRole('button', { name: 'Save member' }));

    await waitFor(() =>
      expect(screen.getByText('A member with that phone number already exists.')).toBeInTheDocument(),
    );
    // The dialog is still open with everything typed still in it.
    expect(screen.getByTestId('new-member')).toBeInTheDocument();
    expect(screen.getByLabelText('Full name')).toHaveValue('Rifat Hasan');
    expect(screen.getByLabelText('Phone number')).toHaveValue('01711000111');
    expect(screen.getByLabelText('Phone number')).toHaveAttribute('aria-invalid', 'true');
    // Registration was tried exactly once, and no wallet call followed it.
    expect(calls.filter((call) => call.method === 'POST' && call.path === '/members')).toHaveLength(1);
    expect(calls.some((call) => call.path.includes('/wallet/'))).toBe(false);
  });

  it('a blank name is caught before the request', async () => {
    const user = await openNewMember();
    await user.type(screen.getByLabelText('Phone number'), '01711000111');
    await user.click(screen.getByRole('button', { name: 'Save member' }));

    expect(await screen.findByText(/Enter the member/)).toBeInTheDocument();
    expect(calls.some((call) => call.method === 'POST' && call.path === '/members')).toBe(false);
  });

  it('save & seat registers, tops up and starts the session — then hands over the Floor', async () => {
    const invalidate = vi.fn();
    const user = await openNewMember();
    client.invalidateQueries = invalidate as unknown as QueryClient['invalidateQueries'];

    await user.type(screen.getByLabelText('Full name'), 'Nadia Karim');
    await user.type(screen.getByLabelText('Phone number'), '01911222333');
    await user.click(screen.getByTestId('save-and-seat'));

    await waitFor(() => expect(push).toHaveBeenCalledWith('/floor'));

    // In order: the member, then their opening wallet, then the seat.
    const writes = calls.filter((call) => call.method === 'POST').map((call) => call.path);
    expect(writes).toEqual(['/members', '/members/12/wallet/topup', '/sessions']);

    const created = calls.find((call) => call.method === 'POST' && call.path === '/members');
    expect(created?.body).toMatchObject({ name: 'Nadia Karim', phone: '01911222333', preferredConsole: 'PS5' });

    // The seat is on the one free console, with the member attached.
    const seat = calls.find((call) => call.path === '/sessions');
    expect(seat?.body).toEqual({ stationId: 2, memberId: 12 });

    // Everything the Floor is about to read has been marked stale (§5.3).
    const keys = invalidate.mock.calls.map((call) => JSON.stringify(call[0]?.queryKey));
    expect(keys).toContain(JSON.stringify(['stations']));
    expect(keys).toContain(JSON.stringify(['sessions', 55]));
    expect(keys).toContain(JSON.stringify(['members']));

    expect(useAppStore.getState().selectedStationId).toBe(2);
    expect(screen.queryByTestId('new-member')).not.toBeInTheDocument();
  });

  it('a seat refused after the member is saved keeps the member and says what happened', async () => {
    serve({ startSession: () => conflict('STATION_BUSY', 'someone got there first') });
    const user = await openNewMember();

    await user.type(screen.getByLabelText('Full name'), 'Nadia Karim');
    await user.type(screen.getByLabelText('Phone number'), '01911222333');
    await user.click(screen.getByTestId('save-and-seat'));

    await waitFor(() => expect(screen.getByTestId('new-member-notice')).toBeInTheDocument());
    expect(screen.getByTestId('new-member-notice')).toHaveTextContent(
      'That console already has a live session.',
    );
    expect(push).not.toHaveBeenCalled();

    // The retry re-runs only what failed: no second member, no second top-up —
    // that key was released on success, so sending it again would credit twice.
    await user.click(screen.getByTestId('save-and-seat'));
    await waitFor(() => expect(calls.filter((call) => call.path === '/sessions')).toHaveLength(2));
    expect(calls.filter((call) => call.method === 'POST' && call.path === '/members')).toHaveLength(1);
    expect(calls.filter((call) => call.path.endsWith('/wallet/topup'))).toHaveLength(1);
  });

  it('no free console means no seat button to press', async () => {
    const busy: Station[] = STATIONS.map((station) => ({ ...station, floorState: 'RUNNING' }));
    fetchMock.mockImplementation((input: RequestInfo, init?: RequestInit) => {
      const url = new URL(String(input));
      if (url.pathname.endsWith('/stations')) return json(busy);
      return json({ content: [RIFAT], page: 0, size: 50, totalElements: 1 });
    });
    const user = await openNewMember();

    const seat = screen.getByTestId('save-and-seat');
    expect(seat).toBeDisabled();
    expect(seat).toHaveTextContent('No free console');
    await user.click(seat);
    expect(calls.some((call) => call.path === '/sessions')).toBe(false);
  });
});
