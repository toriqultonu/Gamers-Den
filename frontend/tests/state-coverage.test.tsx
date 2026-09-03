/**
 * F16 — the state-coverage sweep: every screen S1–S14 against design.md §1.
 *
 * "All screens implement: default, loading (skeletons matching layout), empty,
 * error (never destroys entered data), permission-denied (UI hides affordance
 * AND API 403 renders as an access notice)" — and
 * frontend/ARCHITECTURE.md §5.10 turns that sentence into a gate: "Every screen
 * ships all five states … A screen without them is not done."
 *
 * The per-screen suites (`floor.test.tsx`, `bookings.test.tsx`, …) already pin
 * what each screen *means* in each state. What this file pins is that the row
 * exists at all: one table, one loop, one line per screen, so a fourteenth
 * screen with no empty state fails here rather than being noticed on the floor.
 * Every screen is driven through the same four servers — a read that never
 * answers, a read that answers with nothing, a 500, and a 403 — because that is
 * how the counter meets them.
 *
 * S1 is the exception at both ends: it has no server read to be pending on and
 * no role to be refused, so its own three states (roster, wrong PIN, lockout)
 * are asserted separately at the bottom.
 */

import type { ReactElement } from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { BookingsScreen } from '@/components/domain/bookings-screen';
import { ExpensesScreen } from '@/components/domain/expenses-screen';
import { FloorScreen } from '@/components/domain/floor-screen';
import { InventoryScreen } from '@/components/domain/inventory-screen';
import { LoginScreen } from '@/components/domain/login-screen';
import { MembersScreen } from '@/components/domain/members-screen';
import { OverviewScreen } from '@/components/domain/overview-screen';
import { PosScreen } from '@/components/domain/pos-screen';
import { PrintPreviewScreen } from '@/components/domain/print-preview-screen';
import { ReportsScreen } from '@/components/domain/reports-screen';
import { SettingsScreen } from '@/components/domain/settings-screen';
import { SetupScreen } from '@/components/domain/setup-screen';
import { ShiftScreen } from '@/components/domain/shift-screen';
import { TournamentsScreen } from '@/components/domain/tournaments-screen';

import { SessionProvider } from '@/features/auth/session';
import { makeQueryClient } from '@/lib/query-client';
import { forgetSession, resetIdempotencyKeys } from '@/lib/api';
import { resetServerTime } from '@/lib/time';
import { resetPosStore, useAppStore } from '@/features/pos/bill-store';

const NOW = '2026-09-03T12:00:00Z';

// The screens are mounted directly, outside the App Router that normally
// provides these — S6 routes to Floor after "Save & seat", S1 after sign-in.
vi.mock('next/navigation', () => ({
  useRouter: () => ({
    replace: vi.fn(),
    push: vi.fn(),
    prefetch: vi.fn(),
    refresh: vi.fn(),
    back: vi.fn(),
  }),
  usePathname: () => '/',
  useSearchParams: () => new URLSearchParams(),
}));

/* --------------------------------------------------------------- servers */

/** The four shapes every screen has to survive (design.md §1). */
type Mode = 'empty' | 'error' | 'forbidden';

const fetchMock = vi.fn();

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', Date: new Date(NOW).toUTCString() },
  });
}

function envelope(code: string, message: string, status: number) {
  return json({ error: { code, message, traceId: 't-state' } }, status);
}

/**
 * What "nothing yet" looks like per endpoint. A list answers `[]`, a document
 * answers `{}` — the screens read every field defensively, so this is the
 * honest first-day server rather than a fixture with the rows removed.
 */
function emptyBody(path: string): unknown {
  if (path === '/members') return { content: [], page: 0, size: 50, totalElements: 0 };
  if (
    path === '/stations' ||
    path === '/items' ||
    path === '/pricing' ||
    path === '/staff' ||
    path === '/bookings' ||
    path === '/play-queue' ||
    path === '/expenses' ||
    path === '/alerts' ||
    path === '/printers' ||
    path === '/tournaments' ||
    path === '/tournaments/history'
  ) {
    return [];
  }
  if (path === '/booking-settings') {
    return { enabled: true, packageFee: 100, cancelCutoffHours: 2 };
  }
  return {};
}

/** Every GET answers the same way — which is exactly the state under test. */
function serve(mode: Mode, overrides: Record<string, () => Response> = {}) {
  fetchMock.mockImplementation((input: RequestInfo) => {
    const path = new URL(String(input)).pathname.replace('/api/v1', '');
    const override = overrides[path];
    if (override) return override();
    if (mode === 'forbidden') {
      return envelope('FORBIDDEN', 'Your role does not have access to this.', 403);
    }
    if (mode === 'error') {
      return envelope('INTERNAL', 'The venue server could not answer.', 500);
    }
    return json(emptyBody(path));
  });
}

/** The loading state: a read that has been sent and has not come back. */
function serveNever() {
  fetchMock.mockImplementation(() => new Promise<Response>(() => {}));
}

let client: QueryClient;

function mount(node: ReactElement) {
  client = makeQueryClient();
  client.setDefaultOptions({ queries: { retry: false } });
  return render(
    <QueryClientProvider client={client}>
      <SessionProvider>{node}</SessionProvider>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock);
  fetchMock.mockReset();
  resetPosStore();
  forgetSession();
  resetServerTime();
  resetIdempotencyKeys();
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

/* ----------------------------------------------------------- the table */

type ScreenRow = {
  /** The design.md §1 row this covers. */
  id: string;
  name: string;
  /** The screen as the route mounts it, in its default (allowed) role. */
  node: () => ReactElement;
  /** The skeleton that stands in for the layout while the read is out. */
  skeleton: string;
  /** What is on screen when the venue has no rows yet. */
  empty: string;
  /** Extra servers the empty state needs — S7's "no shift" is a 409. */
  emptyOverrides?: Record<string, () => Response>;
  /** The banner a failed read raises, over an intact screen. */
  error: string;
  /** The same screen mounted for a role the route refuses, when it has one. */
  deniedNode?: () => ReactElement;
};

const SCREENS: ScreenRow[] = [
  {
    id: 'S2',
    name: 'Overview',
    node: () => <OverviewScreen role="ADMIN" />,
    skeleton: 'overview-skeleton',
    empty: 'live-stations-empty',
    error: 'overview-error',
    deniedNode: () => <OverviewScreen role="CASHIER" />,
  },
  {
    id: 'S3',
    name: 'Floor',
    node: () => <FloorScreen />,
    skeleton: 'floor-skeleton',
    empty: 'floor-empty',
    error: 'floor-error',
  },
  {
    id: 'S4',
    name: 'Point of sale',
    node: () => <PosScreen />,
    skeleton: 'menu-skeleton',
    empty: 'menu-empty',
    error: 'pos-error',
  },
  {
    id: 'S5',
    name: 'Inventory',
    node: () => <InventoryScreen />,
    skeleton: 'inventory-skeleton',
    empty: 'data-table-empty',
    error: 'inventory-error',
  },
  {
    id: 'S6',
    name: 'Members',
    node: () => <MembersScreen />,
    skeleton: 'members-skeleton',
    empty: 'members-empty',
    error: 'members-error',
  },
  {
    id: 'S7',
    name: 'Shift close',
    node: () => <ShiftScreen />,
    skeleton: 'shift-skeleton',
    // A terminal with no shift open is S7's empty, and the backend says so with
    // a plain 409 (`features/shift/queries.ts`).
    empty: 'no-shift',
    emptyOverrides: {
      '/shifts/current/x-report': () => envelope('CONFLICT', 'No shift is open.', 409),
    },
    error: 'shift-error',
  },
  {
    id: 'S8',
    name: 'Expenses',
    node: () => <ExpensesScreen />,
    skeleton: 'expenses-skeleton',
    empty: 'data-table-empty',
    error: 'expenses-error',
  },
  {
    id: 'S9',
    name: 'Reports',
    node: () => <ReportsScreen role="ADMIN" />,
    skeleton: 'reports-skeleton',
    empty: 'trend-empty',
    error: 'reports-error',
    deniedNode: () => <ReportsScreen role="CASHIER" />,
  },
  {
    id: 'S10',
    name: 'Setup',
    node: () => <SetupScreen role="ADMIN" />,
    skeleton: 'stations-skeleton',
    empty: 'data-table-empty',
    error: 'stations-error',
    deniedNode: () => <SetupScreen role="CASHIER" />,
  },
  {
    id: 'S11',
    name: 'Print preview',
    node: () => <PrintPreviewScreen jobId={7} />,
    skeleton: 'print-facts-skeleton',
    // Nothing to preview: the URL carried something that is not a job number.
    empty: 'print-invalid',
    error: 'print-job-error',
  },
  {
    id: 'S12',
    name: 'Tournaments',
    node: () => <TournamentsScreen role="MANAGER" />,
    skeleton: 'tournaments-skeleton',
    empty: 'tournaments-empty',
    error: 'tournaments-error',
  },
  {
    id: 'S13',
    name: 'Settings',
    node: () => <SettingsScreen role="ADMIN" />,
    skeleton: 'settings-skeleton',
    // S13 has no rows to be missing — the terminal always has a settings row,
    // and an unset one reads as the documented defaults (design.md §6).
    empty: 'settings-screen',
    error: 'settings-error',
  },
  {
    id: 'S14',
    name: 'Bookings',
    node: () => <BookingsScreen />,
    skeleton: 'bookings-skeleton',
    empty: 'bookings-empty-upcoming',
    error: 'bookings-error',
  },
];

describe.each(SCREENS)('$id $name — design.md §1 state table', (row) => {
  it('draws a loading skeleton in place of the layout', async () => {
    serveNever();
    mount(row.node());
    expect(await screen.findByTestId(row.skeleton)).toBeInTheDocument();
  });

  it('says so when there is nothing to show, rather than drawing an empty frame', async () => {
    serve('empty', row.emptyOverrides);
    // S11's "nothing" is a URL that is not a job number, not an empty read.
    mount(row.id === 'S11' ? <PrintPreviewScreen jobId={null} /> : row.node());
    expect(await screen.findByTestId(row.empty)).toBeInTheDocument();
  });

  it('raises a notice when the read fails, and keeps the screen', async () => {
    serve('error');
    mount(row.node());
    const notice = await screen.findByTestId(row.error);
    expect(notice).toBeInTheDocument();
    // Never a blank page behind it: the header, the rail or the frame stays.
    expect(document.body.textContent?.trim().length ?? 0).toBeGreaterThan(
      notice.textContent?.trim().length ?? 0,
    );
  });

  it('renders a 403 from the API as the access notice', async () => {
    serve('forbidden');
    mount(row.node());
    expect(await screen.findByTestId('access-notice')).toBeInTheDocument();
  });

  const deniedNode = row.deniedNode;
  if (deniedNode) {
    it('hides itself from a role the route refuses, without asking the API', async () => {
      serve('empty');
      mount(deniedNode());
      expect(await screen.findByTestId('access-notice')).toBeInTheDocument();
      // "UI hides affordance" means the guarded read is never fired at all.
      const guarded = fetchMock.mock.calls.filter((call) =>
        /\/(overview|reports|staff|pricing)/.test(String(call[0])),
      );
      expect(guarded).toHaveLength(0);
    });
  }
});

/* ------------------------------------------------- S14's two empty tabs */

describe('S14 Bookings — the states design.md §1 spells out for this screen', () => {
  it('gives History its own empty sentence, not Upcoming’s', async () => {
    serve('empty');
    useAppStore.getState().setBookingsTab('history');
    mount(<BookingsScreen />);
    expect(await screen.findByTestId('bookings-empty-history')).toBeInTheDocument();
    expect(screen.queryByTestId('bookings-empty-upcoming')).not.toBeInTheDocument();
  });

  it('says pre-booking is off and refuses a new one, while the paid ones stand', async () => {
    serve('empty', {
      '/booking-settings': () =>
        json({ enabled: false, packageFee: 100, cancelCutoffHours: 2 }),
    });
    mount(<BookingsScreen />);
    await waitFor(() => expect(screen.getByTestId('prebooking-disabled')).toBeInTheDocument());
    expect(screen.getByTestId('new-booking')).toBeDisabled();
  });
});

/* --------------------------------------------------------- S1 Login */

describe('S1 Login — design.md §1 (“Wrong PIN” inline; 5-try lockout)', () => {
  it('falls back to a staff-ID field on a terminal nobody has signed in on', async () => {
    serve('empty');
    mount(<LoginScreen />);
    expect(await screen.findByLabelText(/staff id/i)).toBeInTheDocument();
  });

  it('shows a wrong PIN inline and keeps the operator on the screen', async () => {
    const user = userEvent.setup();
    serve('empty', {
      '/auth/login': () =>
        json(
          {
            error: {
              code: 'UNAUTHORIZED',
              message: 'Wrong staff id or PIN',
              details: { attemptsRemaining: 3 },
            },
          },
          401,
        ),
    });
    mount(<LoginScreen />);

    await user.type(await screen.findByLabelText(/staff id/i), '1');
    await user.type(screen.getByLabelText('PIN'), '1234');
    await user.click(screen.getByRole('button', { name: /sign in/i }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/wrong pin/i);
    // The identity survives the refusal — an error never undoes what was typed.
    expect(screen.getByLabelText(/staff id/i)).toHaveValue('1');
  });

  it('locks the identity out after the fifth try, in its own notice', async () => {
    const user = userEvent.setup();
    serve('empty', {
      '/auth/login': () =>
        json(
          {
            error: {
              code: 'LOCKED_PIN',
              message: 'PIN locked after 5 failed attempts',
              details: { staffId: 1, lockedUntil: '2026-09-03T21:34:00+06:00', retryAfterSeconds: 900 },
            },
          },
          423,
        ),
    });
    mount(<LoginScreen />);

    await user.type(await screen.findByLabelText(/staff id/i), '1');
    await user.type(screen.getByLabelText('PIN'), '1234');
    await user.click(screen.getByRole('button', { name: /sign in/i }));

    expect(await screen.findByTestId('lockout-notice')).toBeInTheDocument();
  });
});
