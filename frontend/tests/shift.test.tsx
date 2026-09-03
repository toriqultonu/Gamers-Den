/**
 * S7 — Shift close and S8 — Expenses (design.md §1 S7/S8 + §5 P2/P3/P4,
 * docs/bookings.md §6).
 *
 * State-table assertions, not snapshots. What is pinned here is the handful of
 * rules a drawer is not allowed to get wrong:
 *
 *  - the discrepancy is `counted − expected` and moves while the operator
 *    types, in the same sign convention the Z prints;
 *  - the pre-booking strip reconciles `booking_amount` — shown while the
 *    feature is on, and still shown when it is off but money was taken anyway,
 *    because that money is in the drawer either way;
 *  - **the close is never optimistic**: a refusal leaves every figure and every
 *    typed field exactly as it was, and only the server's own report is ever
 *    rendered as the Z;
 *  - a close that succeeds signs the terminal out and lands on S1;
 *  - the expense form validates before it posts, and `?voucher=true` is what
 *    turns a row into a P4 job — reported from the response, never promised.
 */

import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ShiftScreen } from '@/components/domain/shift-screen';
import { ExpensesScreen } from '@/components/domain/expenses-screen';
import { makeQueryClient } from '@/lib/query-client';
import { forgetSession, resetIdempotencyKeys } from '@/lib/api';
import { resetServerTime } from '@/lib/time';
import { SessionProvider } from '@/features/auth/session';
import { STAFF_ROSTER_KEY } from '@/features/auth/staff-roster';
import {
  createExpenseSchema,
  discrepancyNote,
  discrepancyOf,
  discrepancyValue,
  drawerState,
  expectedWorking,
  expenseTotals,
  largestCategory,
  reconciliationStrips,
  recordedBy,
  takingsRows,
  takingsTotals,
  type Expense,
  type ShiftReport,
} from '@/features/shift/schemas';

const NOW = '2026-09-03T14:00:00Z'; // 20:00 in Dhaka

const replace = vi.fn();

vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace, push: vi.fn(), prefetch: vi.fn(), refresh: vi.fn(), back: vi.fn() }),
  usePathname: () => '/shift',
  useSearchParams: () => new URLSearchParams(),
}));

/* ------------------------------------------------------------- fixtures */

/** float 3,000 + cash takings 6,900 − petty cash 480 = 9,420 expected. */
const REPORT: ShiftReport = {
  kind: 'X',
  shiftId: 12,
  terminal: 'COUNTER-1',
  staffId: 4,
  openedAt: '2026-09-03T10:00:00Z',
  serverTime: NOW,
  openingFloat: 3000,
  takings: {
    byMethod: [
      { method: 'CASH', gaming: 4200, fnb: 1500, tournament: 800, booking: 400, total: 6900 },
      { method: 'BKASH', gaming: 1200, fnb: 300, tournament: 400, booking: 300, total: 2200 },
      { method: 'NAGAD', gaming: 0, fnb: 0, tournament: 0, booking: 0, total: 0 },
      { method: 'WALLET', gaming: 500, fnb: 0, tournament: 0, booking: 0, total: 500 },
    ],
    totals: { method: 'TOTAL', gaming: 5900, fnb: 1800, tournament: 1200, booking: 700, total: 9600 },
    pointsRedeemed: 120,
    pointsEarned: 480,
    saleCount: 36,
    refundCount: 2,
  },
  expenses: {
    total: 480,
    count: 2,
    byCategory: [
      { category: 'SUPPLIES', amount: 300 },
      { category: 'REPAIRS', amount: 180 },
    ],
    lines: [
      {
        id: 71,
        description: 'Water delivery',
        category: 'SUPPLIES',
        amount: 300,
        at: '2026-09-03T11:30:00Z',
      },
      {
        id: 72,
        description: 'Controller cable',
        category: 'REPAIRS',
        amount: 180,
        at: '2026-09-03T12:15:00Z',
      },
    ],
  },
  cash: { openingFloat: 3000, takings: 6900, expenses: 480, expected: 9420 },
};

const EXPENSES: Expense[] = [
  {
    id: 72,
    shiftId: 12,
    staffId: 4,
    description: 'Controller cable',
    category: 'REPAIRS',
    amount: 180,
    createdAt: '2026-09-03T12:15:00Z',
  },
  {
    id: 71,
    shiftId: 12,
    staffId: 9,
    description: 'Water delivery',
    category: 'SUPPLIES',
    amount: 300,
    createdAt: '2026-09-03T11:30:00Z',
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

function conflict(code: string, message: string) {
  return json({ error: { code, message, traceId: 't-1' } }, 409);
}

type Handlers = {
  report?: () => Response;
  settings?: () => Response;
  close?: () => Response;
  open?: () => Response;
  expenses?: () => Response;
  record?: (body: Record<string, unknown>) => Response;
};

const calls: {
  method: string;
  path: string;
  query: URLSearchParams;
  body: Record<string, unknown>;
}[] = [];

function serve(handlers: Handlers = {}) {
  fetchMock.mockImplementation((input: RequestInfo, init?: RequestInit) => {
    const url = new URL(String(input));
    const path = url.pathname.replace('/api/v1', '');
    const method = (init?.method ?? 'GET').toUpperCase();
    const body = init?.body ? (JSON.parse(String(init.body)) as Record<string, unknown>) : {};
    calls.push({ method, path, query: url.searchParams, body });

    if (method === 'GET' && path === '/booking-settings') {
      return handlers.settings?.() ?? json({ enabled: true, packageFee: 100, cancelCutoffHours: 2 });
    }
    if (method === 'GET' && path === '/shifts/current/x-report') {
      return handlers.report?.() ?? json(REPORT);
    }
    if (method === 'POST' && path === '/shifts/current/close') {
      return (
        handlers.close?.() ??
        json({
          ...REPORT,
          kind: 'Z',
          closedAt: NOW,
          printJobId: 810,
          cash: { ...REPORT.cash, counted: 9120, discrepancy: -300 },
        })
      );
    }
    if (method === 'POST' && path === '/shifts') {
      return handlers.open?.() ?? json({ id: 13, terminal: 'COUNTER-1', openingFloat: 3000 }, 201);
    }
    if (method === 'GET' && path === '/expenses') {
      return handlers.expenses?.() ?? json(EXPENSES);
    }
    if (method === 'POST' && path === '/expenses') {
      if (handlers.record) return handlers.record(body);
      const voucher = url.searchParams.get('voucher') === 'true';
      return json(
        {
          id: 73,
          shiftId: 12,
          staffId: 4,
          description: String(body.description ?? ''),
          category: String(body.category ?? 'OTHER'),
          amount: Number(body.amount ?? 0),
          createdAt: NOW,
          printJobId: voucher ? 820 : undefined,
        },
        201,
      );
    }
    if (method === 'POST' && path === '/auth/logout') return json({});

    return json({});
  });
}

let client: QueryClient;

function renderScreen(node: React.ReactNode) {
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
  calls.length = 0;
  replace.mockReset();
  window.localStorage.clear();
  forgetSession();
  resetServerTime();
  resetIdempotencyKeys();
  serve();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

async function openShiftScreen() {
  const user = userEvent.setup();
  renderScreen(<ShiftScreen />);
  await waitFor(() => expect(screen.getByTestId('shift-screen')).toBeInTheDocument());
  return user;
}

async function openExpenses() {
  const user = userEvent.setup();
  renderScreen(<ExpensesScreen />);
  await waitFor(() => expect(screen.getByTestId('expense-form')).toBeInTheDocument());
  return user;
}

const postCalls = (path: string) =>
  calls.filter((call) => call.method === 'POST' && call.path === path);

/** The form's own category chip — the label also appears in the table. */
const categoryChip = (label: string) =>
  within(screen.getByRole('group', { name: 'Category' })).getByText(label);

/* ------------------------------------------------------------------ pure */

describe('drawer arithmetic', () => {
  it('is counted − expected, with the Z’s own sign convention', () => {
    expect(discrepancyOf(9420, null)).toBeNull();
    expect(discrepancyOf(9420, 9420)).toBe(0);
    expect(discrepancyOf(9420, 9120)).toBe(-300);
    expect(discrepancyOf(9420, 9600)).toBe(180);

    expect(drawerState(null)).toBe('uncounted');
    expect(drawerState(0)).toBe('balanced');
    expect(drawerState(-300)).toBe('short');
    expect(drawerState(180)).toBe('over');
  });

  it('renders the figure with an explicit sign, and a dash before anyone counts', () => {
    expect(discrepancyValue(null)).toBe('—');
    expect(discrepancyValue(0)).toBe('৳0');
    expect(discrepancyValue(-300)).toBe('−৳300');
    expect(discrepancyValue(180)).toBe('+৳180');

    expect(discrepancyNote(null)).toMatch(/count the notes/i);
    expect(discrepancyNote(0)).toMatch(/balances/i);
    expect(discrepancyNote(-300)).toMatch(/৳300 short/);
    expect(discrepancyNote(180)).toMatch(/৳180 over/);
  });

  it('shows how expected got there — float + cash takings − petty cash', () => {
    expect(expectedWorking(REPORT.cash)).toBe('৳3,000 float + ৳6,900 cash takings − ৳480 petty cash');
  });
});

describe('the takings matrix', () => {
  it('keeps every tender method in order, zero rows included', () => {
    expect(takingsRows(REPORT.takings).map((row) => row.method)).toEqual([
      'CASH',
      'BKASH',
      'NAGAD',
      'WALLET',
    ]);
    // A shift with no sales still has all four rows rather than an empty table.
    expect(takingsRows(undefined).map((row) => row.total)).toEqual([0, 0, 0, 0]);
  });

  it('appends a method the server reported that this build does not know', () => {
    const rows = takingsRows({
      byMethod: [{ method: 'CARD', gaming: 100, fnb: 0, tournament: 0, booking: 0, total: 100 }],
    });
    expect(rows.map((row) => row.method)).toEqual(['CASH', 'BKASH', 'NAGAD', 'WALLET', 'CARD']);
  });

  it('takes the bottom line from the server rather than re-summing it', () => {
    expect(takingsTotals(REPORT.takings).total).toBe(9600);
    expect(takingsTotals(undefined).total).toBe(0);
  });
});

describe('the reconciliation strips', () => {
  it('reconciles tournament entries and pre-bookings off the takings columns', () => {
    const strips = reconciliationStrips(REPORT.takings, { prebookingEnabled: true });
    expect(strips.map((strip) => strip.id)).toEqual(['tournament', 'booking']);
    expect(strips[0].amount).toBe(1200);
    expect(strips[1].amount).toBe(700);
  });

  it('drops the pre-booking strip when the feature is off and nothing was sold', () => {
    const takings = {
      ...REPORT.takings,
      totals: { ...REPORT.takings?.totals, booking: 0 },
    };
    expect(
      reconciliationStrips(takings, { prebookingEnabled: false }).map((strip) => strip.id),
    ).toEqual(['tournament']);
  });

  it('keeps it when the feature is off but the drawer holds booking money anyway', () => {
    expect(
      reconciliationStrips(REPORT.takings, { prebookingEnabled: false }).map((strip) => strip.id),
    ).toEqual(['tournament', 'booking']);
  });
});

describe('petty-cash shapes', () => {
  it('validates a row before it posts', () => {
    const blank = createExpenseSchema.safeParse({
      description: '  ',
      category: undefined,
      amount: undefined,
      voucher: false,
    });
    expect(blank.success).toBe(false);

    expect(
      createExpenseSchema.safeParse({
        description: 'Water delivery',
        category: 'SUPPLIES',
        amount: 0,
        voucher: false,
      }).success,
    ).toBe(false);

    const ok = createExpenseSchema.safeParse({
      description: '  Water delivery ',
      category: 'SUPPLIES',
      amount: 300,
      voucher: true,
    });
    expect(ok.success && ok.data.description).toBe('Water delivery');
  });

  it('totals the shift’s petty cash and names its biggest category', () => {
    expect(expenseTotals(EXPENSES)).toEqual({ total: 480, count: 2 });
    expect(expenseTotals(undefined)).toEqual({ total: 0, count: 0 });
    expect(largestCategory(EXPENSES)).toEqual({ category: 'SUPPLIES', amount: 300 });
    expect(largestCategory([])).toBeNull();
  });

  it('names who recorded a row from what the terminal knows, never invented', () => {
    const known = new Map([[9, 'Sabbir Ahmed']]);
    expect(recordedBy(4, known, { id: 4 })).toBe('You');
    expect(recordedBy(9, known, { id: 4 })).toBe('Sabbir Ahmed');
    expect(recordedBy(11, known, { id: 4 })).toBe('Staff #11');
    expect(recordedBy(undefined, known, { id: 4 })).toBe('—');
  });
});

/* ------------------------------------------------------------------- S7 */

describe('S7 — shift close', () => {
  it('renders the X matrix, the header and the expected drawer', async () => {
    await openShiftScreen();

    const takings = screen.getByTestId('takings');
    expect(within(takings).getByText('Cash')).toBeInTheDocument();
    expect(within(takings).getByText('bKash')).toBeInTheDocument();
    expect(within(takings).getByText('Wallet')).toBeInTheDocument();
    expect(screen.getByTestId('takings-total')).toHaveTextContent('৳9,600');

    expect(screen.getByText('৳9,420')).toBeInTheDocument();
    expect(screen.getByText(/৳3,000 float/)).toBeInTheDocument();
    // 36 sales + 2 refunds — what the shift has posted.
    expect(screen.getByText('38')).toBeInTheDocument();
  });

  it('moves the discrepancy while the drawer count is typed', async () => {
    const user = await openShiftScreen();
    const field = screen.getByLabelText(/counted/i);

    expect(screen.getByTestId('discrepancy')).toHaveTextContent('—');
    expect(screen.getByTestId('drawer')).toHaveAttribute('data-state', 'uncounted');

    await user.type(field, '9120');
    expect(screen.getByTestId('discrepancy')).toHaveTextContent('−৳300');
    expect(screen.getByTestId('drawer')).toHaveAttribute('data-state', 'short');
    expect(screen.getByTestId('discrepancy-warning')).toHaveTextContent(/৳300 short/);

    await user.clear(field);
    await user.type(field, '9600');
    expect(screen.getByTestId('discrepancy')).toHaveTextContent('+৳180');
    expect(screen.getByTestId('drawer')).toHaveAttribute('data-state', 'over');

    await user.clear(field);
    await user.type(field, '9420');
    expect(screen.getByTestId('discrepancy')).toHaveTextContent('৳0');
    expect(screen.getByTestId('drawer')).toHaveAttribute('data-state', 'balanced');
    expect(screen.queryByTestId('discrepancy-warning')).not.toBeInTheDocument();
  });

  it('renders the tournament and pre-booking strips with the shift’s own figures', async () => {
    await openShiftScreen();
    expect(screen.getByTestId('strip-tournament')).toHaveTextContent('৳1,200');
    await waitFor(() => expect(screen.getByTestId('strip-booking')).toHaveTextContent('৳700'));
    expect(screen.getByTestId('strip-booking')).toHaveTextContent(/deposits count into the drawer/i);
  });

  it('hides the pre-booking strip when the feature is off and nothing was pre-sold', async () => {
    serve({
      settings: () => json({ enabled: false, packageFee: 0, cancelCutoffHours: 2 }),
      report: () =>
        json({
          ...REPORT,
          takings: { ...REPORT.takings, totals: { ...REPORT.takings?.totals, booking: 0 } },
        }),
    });
    await openShiftScreen();
    expect(screen.getByTestId('strip-tournament')).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.queryByTestId('strip-booking')).not.toBeInTheDocument(),
    );
  });

  it('lists the petty cash that came out of this drawer', async () => {
    await openShiftScreen();
    const list = screen.getByTestId('petty-cash');
    expect(within(list).getByText('Water delivery')).toBeInTheDocument();
    expect(within(list).getByText('−৳180')).toBeInTheDocument();
    expect(list).toHaveTextContent('৳480');
  });

  it('will not close without a count', async () => {
    await openShiftScreen();
    expect(screen.getByTestId('close-shift')).toBeDisabled();
    expect(postCalls('/shifts/current/close')).toHaveLength(0);
  });

  it('closes with the server’s Z, then signs the terminal out to S1', async () => {
    const user = await openShiftScreen();
    await user.type(screen.getByLabelText(/counted/i), '9120');
    await user.type(screen.getByLabelText(/handover/i), 'Till roll running low');
    await user.click(screen.getByTestId('close-shift'));

    await waitFor(() => expect(screen.getByTestId('shift-closed')).toBeInTheDocument());
    const sent = postCalls('/shifts/current/close');
    expect(sent).toHaveLength(1);
    expect(sent[0].body).toEqual({ countedCash: 9120, handoverNote: 'Till roll running low' });

    // The Z on screen is the server's, not the preview: it names its own
    // discrepancy and the print job that carries it.
    const panel = screen.getByTestId('shift-closed');
    expect(panel).toHaveTextContent('−৳300');
    expect(panel).toHaveTextContent('#810');

    await waitFor(() => expect(replace).toHaveBeenCalledWith('/login'));
  });

  it('never closes optimistically — a refusal keeps the desk exactly as it was', async () => {
    serve({
      close: () => conflict('CONFLICT', 'No shift is open on COUNTER-1'),
    });
    const user = await openShiftScreen();
    await user.type(screen.getByLabelText(/counted/i), '9120');
    await user.click(screen.getByTestId('close-shift'));

    await waitFor(() => expect(screen.getByTestId('close-error')).toBeInTheDocument());
    // Still the desk, never the Z; the count and the live figure both survive.
    expect(screen.getByTestId('shift-screen')).toBeInTheDocument();
    expect(screen.queryByTestId('shift-closed')).not.toBeInTheDocument();
    expect(screen.getByLabelText(/counted/i)).toHaveValue('9120');
    expect(screen.getByTestId('discrepancy')).toHaveTextContent('−৳300');
    expect(replace).not.toHaveBeenCalledWith('/login');
  });

  it('prints an interim X without closing anything', async () => {
    const user = await openShiftScreen();
    await user.click(screen.getByTestId('print-x'));

    await waitFor(() =>
      expect(
        calls.some(
          (call) =>
            call.path === '/shifts/current/x-report' && call.query.get('print') === 'true',
        ),
      ).toBe(true),
    );
    expect(postCalls('/shifts/current/close')).toHaveLength(0);
  });

  it('offers to open one when the terminal has no shift', async () => {
    serve({ report: () => conflict('CONFLICT', 'No shift is open on COUNTER-1') });
    const user = userEvent.setup();
    renderScreen(<ShiftScreen />);

    await waitFor(() => expect(screen.getByTestId('no-shift')).toBeInTheDocument());
    expect(screen.getByTestId('open-shift')).toBeDisabled();

    await user.type(screen.getByLabelText(/opening float/i), '3000');
    await user.click(screen.getByTestId('open-shift'));

    await waitFor(() => expect(postCalls('/shifts')).toHaveLength(1));
    expect(postCalls('/shifts')[0].body).toEqual({ openingFloat: 3000 });
  });

  it('renders a 403 as an access notice rather than an empty drawer', async () => {
    serve({
      report: () =>
        json({ error: { code: 'FORBIDDEN', message: 'Not your shift', traceId: 't-2' } }, 403),
    });
    renderScreen(<ShiftScreen />);
    await waitFor(() => expect(screen.getByTestId('access-notice')).toBeInTheDocument());
  });
});

/* ------------------------------------------------------------------- S8 */

describe('S8 — expenses', () => {
  it('lists the shift’s petty cash with who recorded it', async () => {
    window.localStorage.setItem(
      STAFF_ROSTER_KEY,
      JSON.stringify([{ id: 9, name: 'Sabbir Ahmed', role: 'CASHIER' }]),
    );
    await openExpenses();

    expect(await screen.findByText('Water delivery')).toBeInTheDocument();
    expect(screen.getByText('Sabbir Ahmed')).toBeInTheDocument();
    expect(screen.getByText('৳480')).toBeInTheDocument();
  });

  it('refuses to post an incomplete row and says which field is missing', async () => {
    const user = await openExpenses();
    await user.click(screen.getByTestId('record-expense'));

    expect(await screen.findByText(/say what the money was for/i)).toBeInTheDocument();
    expect(screen.getByText(/pick a category/i)).toBeInTheDocument();
    expect(postCalls('/expenses')).toHaveLength(0);

    // A non-integer amount is an inline error, not a rounded charge.
    await user.type(screen.getByLabelText('Description'), 'Water delivery');
    await user.type(screen.getByLabelText('Amount (৳)'), '12.5');
    await user.click(categoryChip('Supplies'));
    await user.click(screen.getByTestId('record-expense'));
    expect(screen.getByText(/whole number of taka/i)).toBeInTheDocument();
    expect(postCalls('/expenses')).toHaveLength(0);
  });

  it('records a row, and only asks for the P4 voucher when the box is ticked', async () => {
    const user = await openExpenses();
    await user.type(screen.getByLabelText('Description'), 'Water delivery');
    await user.type(screen.getByLabelText('Amount (৳)'), '300');
    await user.click(categoryChip('Supplies'));
    await user.click(screen.getByTestId('record-expense'));

    await waitFor(() => expect(postCalls('/expenses')).toHaveLength(1));
    expect(postCalls('/expenses')[0].query.get('voucher')).toBe('false');
    expect(postCalls('/expenses')[0].body).toEqual({
      description: 'Water delivery',
      category: 'SUPPLIES',
      amount: 300,
    });
    // No job was asked for, so none is claimed.
    expect(await screen.findByTestId('expense-recorded')).not.toHaveTextContent(/print job/i);
  });

  it('asks for the voucher job and reports the one the server made', async () => {
    const user = await openExpenses();
    await user.type(screen.getByLabelText('Description'), 'Controller cable');
    await user.type(screen.getByLabelText('Amount (৳)'), '180');
    await user.click(categoryChip('Repairs'));
    await user.click(screen.getByTestId('voucher'));
    await user.click(screen.getByTestId('record-expense'));

    await waitFor(() => expect(postCalls('/expenses')).toHaveLength(1));
    expect(postCalls('/expenses')[0].query.get('voucher')).toBe('true');
    expect(await screen.findByTestId('expense-recorded')).toHaveTextContent('print job #820');
  });

  it('keeps every typed field when the server refuses the row', async () => {
    serve({ record: () => conflict('CONFLICT', 'No shift is open on COUNTER-1') });
    const user = await openExpenses();
    await user.type(screen.getByLabelText('Description'), 'Water delivery');
    await user.type(screen.getByLabelText('Amount (৳)'), '300');
    await user.click(categoryChip('Supplies'));
    await user.click(screen.getByTestId('record-expense'));

    await waitFor(() => expect(screen.getByTestId('expense-error')).toBeInTheDocument());
    expect(screen.getByLabelText('Description')).toHaveValue('Water delivery');
    expect(screen.getByLabelText('Amount (৳)')).toHaveValue('300');
    expect(categoryChip('Supplies')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.queryByTestId('expense-recorded')).not.toBeInTheDocument();
  });

  it('disables the form when no shift is open on this terminal', async () => {
    serve({ expenses: () => conflict('CONFLICT', 'No shift is open on COUNTER-1') });
    renderScreen(<ExpensesScreen />);

    await waitFor(() => expect(screen.getByTestId('expenses-no-shift')).toBeInTheDocument());
    expect(screen.getByTestId('record-expense')).toBeDisabled();
    expect(screen.getByLabelText('Description')).toBeDisabled();
  });

  it('renders a 403 as an access notice', async () => {
    serve({
      expenses: () =>
        json({ error: { code: 'FORBIDDEN', message: 'Not your screen', traceId: 't-3' } }, 403),
    });
    renderScreen(<ExpensesScreen />);
    await waitFor(() => expect(screen.getByTestId('access-notice')).toBeInTheDocument());
  });
});
