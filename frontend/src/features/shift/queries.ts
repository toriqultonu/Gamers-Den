'use client';

/**
 * Shift and petty-cash reads: `['shift', 'current']` and `['expenses']`.
 *
 * `['shift', 'current']` holds the **X report** rather than the shift row.
 * §4.1 names the key; what S7 renders, and what every money write already
 * invalidates (`features/bookings/mutations.ts`, `features/payments`), is the
 * live takings-and-drawer reading — the shift row itself carries nothing that
 * is true before a close.
 *
 * Nothing here is stored server-side: an X is recomputed on every read
 * (backend `ShiftService.interimReport`), which is why it is safe to ask for it
 * as often as a settle happens, and why `?print=true` is deliberately **not**
 * passed by these reads. Printing an X is an operator action with paper
 * attached, so it lives in the mutations file next to the close.
 */

import { useQuery } from '@tanstack/react-query';
import { api, isApiError } from '@/lib/api';
import { queryKeys } from '@/lib/query-keys';
import type { Expense, ShiftReport } from './schemas';

/**
 * The 409 both routes answer when this terminal has no shift open.
 *
 * The backend raises a plain `CONFLICT` for it (`ShiftService.noShiftOpen`) —
 * there is no domain code to switch on — so the screens ask this question
 * rather than string-matching a message.
 */
export function isNoShiftOpen(error: unknown): boolean {
  return isApiError(error) && error.status === 409;
}

export function currentShiftReportQueryOptions() {
  return {
    queryKey: queryKeys.shift.current(),
    queryFn: () => api.get<ShiftReport>('/shifts/current/x-report'),
  };
}

/** `GET /shifts/current/x-report` — the figures S7 counts the drawer against. */
export function useCurrentShiftReport(options: { enabled?: boolean } = {}) {
  return useQuery({
    ...currentShiftReportQueryOptions(),
    enabled: options.enabled ?? true,
  });
}

export function expensesQueryOptions() {
  return {
    queryKey: queryKeys.expenses.all(),
    queryFn: () => api.get<Expense[]>('/expenses'),
  };
}

/**
 * `GET /expenses` — the open shift's petty cash, newest first.
 *
 * Defaults to this terminal's open shift server-side, so the key needs no shift
 * in it: closing one and opening the next replaces the list, which is exactly
 * what the invalidation after a close does.
 */
export function useExpenses(options: { enabled?: boolean } = {}) {
  return useQuery({ ...expensesQueryOptions(), enabled: options.enabled ?? true });
}
