/**
 * Shift and petty-cash shapes — S7 and S8 (design.md §1; api-contract.md,
 * "Shifts & expenses"; backend `shift/web/*`).
 *
 * The screens are two faces of one figure. S7 asks "what should be in this
 * drawer, and what is?"; S8 records the money that came back out of it. They
 * meet in the X report: `cash.expected = openingFloat + cash takings −
 * expenses`, so every row S8 writes moves the number S7 counts against.
 *
 * Everything here is pure. The arithmetic S7 does live — the discrepancy that
 * moves while the operator types — is the *only* money maths this app performs
 * on its own, and it is deliberately a preview: the server recomputes expected,
 * counted and discrepancy inside the closing transaction and writes those onto
 * the shift (backend `ShiftService.close`). What is on screen while typing is
 * the operator's arithmetic done for them, not a promise about the Z.
 */

import { z } from 'zod';
import type { Schemas } from '@/lib/api';
import { formatBDT } from '@/lib/money';

export type ShiftReport = Schemas['ShiftReport'];
export type ShiftTakings = Schemas['ShiftTakings'];
export type ShiftTakingsRow = Schemas['ShiftTakingsRow'];
export type ShiftCash = Schemas['ShiftCash'];
export type ShiftExpenses = Schemas['ShiftExpenses'];
export type ShiftExpenseLine = Schemas['ShiftExpenseLine'];
export type Shift = Schemas['Shift'];
export type Expense = Schemas['Expense'];

/* -------------------------------------------------------- the takings matrix */

/**
 * The tender methods, in the order the report prints them (backend
 * `ShiftTakingsLookup`). The server already sends a row per method even at
 * zero; this list is what the table falls back to so the matrix has a stable
 * shape before the first sale of a shift, and after a backend that adds one.
 */
export const TENDER_METHODS = ['CASH', 'BKASH', 'NAGAD', 'WALLET'] as const;
export type TenderMethod = (typeof TENDER_METHODS)[number];

/** The summary row's method name (`MethodTakings.TOTAL`). */
export const TOTAL_METHOD = 'TOTAL';

export const METHOD_LABELS: Record<string, string> = {
  CASH: 'Cash',
  BKASH: 'bKash',
  NAGAD: 'Nagad',
  WALLET: 'Wallet',
  TOTAL: 'Total',
};

export function methodLabel(method: string | undefined): string {
  if (!method) return '—';
  return METHOD_LABELS[method] ?? method;
}

const ZERO_ROW: Required<ShiftTakingsRow> = {
  method: '',
  gaming: 0,
  fnb: 0,
  tournament: 0,
  booking: 0,
  total: 0,
};

/** One row with every column present — the table never renders `undefined`. */
export function takingsRow(row: ShiftTakingsRow | undefined): Required<ShiftTakingsRow> {
  return { ...ZERO_ROW, ...row, method: row?.method ?? '' };
}

/**
 * The matrix body: the canonical four methods first, in order, then anything
 * else the server reported (a tender added after this build) so a new method
 * shows up as a row rather than as money that quietly stops adding up.
 */
export function takingsRows(takings: ShiftTakings | undefined): Required<ShiftTakingsRow>[] {
  const sent = new Map<string, ShiftTakingsRow>();
  for (const row of takings?.byMethod ?? []) {
    if (row.method && row.method !== TOTAL_METHOD) sent.set(row.method, row);
  }
  const known = TENDER_METHODS.map((method) =>
    takingsRow({ ...sent.get(method), method }),
  );
  const extra = [...sent.entries()]
    .filter(([method]) => !(TENDER_METHODS as readonly string[]).includes(method))
    .map(([method, row]) => takingsRow({ ...row, method }));
  return [...known, ...extra];
}

/** The bottom line. Taken from the server's own `totals`, never re-summed. */
export function takingsTotals(takings: ShiftTakings | undefined): Required<ShiftTakingsRow> {
  return takingsRow({ ...takings?.totals, method: TOTAL_METHOD });
}

/* ------------------------------------------------------ reconciliation strips */

/**
 * The two strips above the drawer (design.md §1, S7): tournament entries and
 * pre-bookings, each pulled straight out of the takings matrix's own column
 * (`transactions.tournament_amount` / `booking_amount` — docs/bookings.md §6).
 *
 * Amounts only. The report carries no per-strip *count* — `saleCount` is the
 * whole shift — and a ticket count invented from the money would be a guess
 * printed next to a figure that is not one.
 */
export type ReconciliationStrip = {
  id: 'tournament' | 'booking';
  label: string;
  note: string;
  amount: number;
};

export const TOURNAMENT_STRIP_NOTE =
  'Every entry sold at the POS logs here automatically — the drawer reconciles with entry fees included in the takings above.';

export const BOOKING_STRIP_NOTE =
  'Deposits count into the drawer; a refunded cancellation reverses automatically.';

/**
 * Which strips S7 shows.
 *
 * Tournaments are always reconciled — the module has no off switch. Pre-booking
 * does (design.md §1, S10), so its strip follows the flag **unless money was
 * taken anyway**: a shift that sold bookings before the owner switched the
 * feature off still has to reconcile them, and hiding the line would leave the
 * drawer long by exactly that amount with nothing on screen to explain it.
 */
export function reconciliationStrips(
  takings: ShiftTakings | undefined,
  options: { prebookingEnabled?: boolean } = {},
): ReconciliationStrip[] {
  const totals = takingsTotals(takings);
  const strips: ReconciliationStrip[] = [
    {
      id: 'tournament',
      label: 'Tournament entries this shift',
      note: TOURNAMENT_STRIP_NOTE,
      amount: totals.tournament,
    },
  ];
  if (options.prebookingEnabled !== false || totals.booking !== 0) {
    strips.push({
      id: 'booking',
      label: 'Pre-booking deposits this shift',
      note: BOOKING_STRIP_NOTE,
      amount: totals.booking,
    });
  }
  return strips;
}

/* ---------------------------------------------------------------- the drawer */

/**
 * `counted − expected`, or `null` while nobody has typed a count.
 *
 * Positive is over, negative is short — the same sign convention as the Z
 * (`CashCount.discrepancy`), so the figure on screen and the figure on the
 * paper never disagree about which way the drawer is out.
 */
export function discrepancyOf(expected: number, counted: number | null): number | null {
  if (counted === null) return null;
  return counted - expected;
}

export type DrawerState = 'uncounted' | 'balanced' | 'over' | 'short';

export function drawerState(discrepancy: number | null): DrawerState {
  if (discrepancy === null) return 'uncounted';
  if (discrepancy === 0) return 'balanced';
  return discrepancy > 0 ? 'over' : 'short';
}

/** The big figure in the discrepancy tile: `—`, `৳0`, `+৳300`, `−৳300`. */
export function discrepancyValue(discrepancy: number | null): string {
  if (discrepancy === null) return '—';
  return formatBDT(discrepancy, { sign: discrepancy === 0 ? 'auto' : 'always' });
}

/** The line under it — what the operator is being told to do about it. */
export function discrepancyNote(discrepancy: number | null): string {
  switch (drawerState(discrepancy)) {
    case 'uncounted':
      return 'Count the notes and coins in the drawer to see the discrepancy.';
    case 'balanced':
      return 'The drawer balances.';
    case 'over':
      return `The drawer is ${formatBDT(Math.abs(discrepancy ?? 0))} over. Closing with a discrepancy alerts the owner.`;
    default:
      return `The drawer is ${formatBDT(Math.abs(discrepancy ?? 0))} short. Closing with a discrepancy alerts the owner.`;
  }
}

/** "৳3,000 float + ৳6,900 cash takings − ৳480 petty cash" — how expected got there. */
export function expectedWorking(cash: ShiftCash | undefined): string {
  const float = cash?.openingFloat ?? 0;
  const takings = cash?.takings ?? 0;
  const expenses = cash?.expenses ?? 0;
  return `${formatBDT(float)} float + ${formatBDT(takings)} cash takings − ${formatBDT(expenses)} petty cash`;
}

/** How many postings the shift has behind it — the header's "Transactions" tile. */
export function postingCount(takings: ShiftTakings | undefined): number {
  return (takings?.saleCount ?? 0) + (takings?.refundCount ?? 0);
}

/* ------------------------------------------------------------------- the close */

/**
 * `POST /shifts/current/close`. The count is required and never defaulted —
 * "a close with no figure would produce a Z whose discrepancy line means
 * nothing" (backend `CloseShiftRequest`) — and the note is the free text the
 * next operator reads.
 */
export const closeShiftSchema = z.object({
  countedCash: z
    .int('Enter the counted cash as a whole number of taka.')
    .min(0, 'A drawer count cannot be negative.')
    .max(10_000_000),
  handoverNote: z.string().trim().max(500, 'Keep the handover note under 500 characters.').optional(),
});

export type CloseShiftInput = z.infer<typeof closeShiftSchema>;

/* ---------------------------------------------------------------- opening one */

/** `POST /shifts` — what is in the drawer before the first sale. */
export const openShiftSchema = z.object({
  openingFloat: z
    .int('Enter the float as a whole number of taka.')
    .min(0, 'An opening float cannot be negative.')
    .max(10_000_000),
});

export type OpenShiftInput = z.infer<typeof openShiftSchema>;

/* ------------------------------------------------------------------ petty cash */

/** `expenses.category` (backend `ExpenseCategory`). */
export const EXPENSE_CATEGORIES = ['SUPPLIES', 'UTILITIES', 'REPAIRS', 'STAFF', 'OTHER'] as const;
export type ExpenseCategory = (typeof EXPENSE_CATEGORIES)[number];

export const EXPENSE_CATEGORY_LABELS: Record<ExpenseCategory, string> = {
  SUPPLIES: 'Supplies',
  UTILITIES: 'Utilities',
  REPAIRS: 'Repairs',
  STAFF: 'Staff',
  OTHER: 'Other',
};

export function expenseCategoryLabel(category: string | undefined): string {
  if (!category) return '—';
  return EXPENSE_CATEGORY_LABELS[category as ExpenseCategory] ?? category;
}

/**
 * `POST /expenses`. The lengths are the server's (`@Size(max = 200)`,
 * `@Positive`), so what the column cannot hold is an inline error rather than a
 * 400 — and the amount is a whole number of taka like every other figure that
 * crosses the wire.
 */
export const createExpenseSchema = z.object({
  description: z
    .string()
    .trim()
    .min(1, 'Say what the money was for.')
    .max(200, 'Keep the description under 200 characters.'),
  category: z.enum(EXPENSE_CATEGORIES, { message: 'Pick a category.' }),
  amount: z
    .int('Enter the amount as a whole number of taka.')
    .min(1, 'An expense must be above zero.')
    .max(1_000_000),
  /** `?voucher=true` — the P4 slip somebody signs for the money (design.md §5). */
  voucher: z.boolean(),
});

export type CreateExpenseInput = z.infer<typeof createExpenseSchema>;

/** The S8 header tiles: what this drawer has paid out, and over how many rows. */
export function expenseTotals(expenses: readonly Expense[] | undefined): {
  total: number;
  count: number;
} {
  const rows = expenses ?? [];
  return {
    total: rows.reduce((sum, row) => sum + (row.amount ?? 0), 0),
    count: rows.length,
  };
}

/** The biggest category, for the third tile — `null` until something is spent. */
export function largestCategory(
  expenses: readonly Expense[] | undefined,
): { category: string; amount: number } | null {
  const totals = new Map<string, number>();
  for (const row of expenses ?? []) {
    const key = row.category ?? 'OTHER';
    totals.set(key, (totals.get(key) ?? 0) + (row.amount ?? 0));
  }
  let best: { category: string; amount: number } | null = null;
  for (const [category, amount] of totals) {
    if (!best || amount > best.amount) best = { category, amount };
  }
  return best;
}

/**
 * Who recorded a row. The expense carries a `staffId`, not a name — there is no
 * roster endpoint a cashier may read (`GET /staff` is Admin-only) — so this
 * names the people this terminal already knows and falls back to the id rather
 * than inventing one.
 */
export function recordedBy(
  staffId: number | undefined,
  known: ReadonlyMap<number, string>,
  self?: { id: number } | null,
): string {
  if (typeof staffId !== 'number') return '—';
  if (self && self.id === staffId) return 'You';
  return known.get(staffId) ?? `Staff #${staffId}`;
}

/* ------------------------------------------------------------------- reading */

/** The first inline error for a field, from a `safeParse` failure. */
export function fieldError(
  error: z.ZodError<unknown> | null | undefined,
  field: string,
): string | undefined {
  if (!error) return undefined;
  return error.issues.find((issue) => issue.path[0] === field)?.message;
}
