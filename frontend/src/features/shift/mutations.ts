'use client';

/**
 * Shift and petty-cash writes: open, close, record an expense, print an X.
 *
 * **The close is never optimistic** (frontend/ARCHITECTURE.md §5.3), and it is
 * the strictest case of the rule in the app. Closing a shift is one server
 * transaction that snapshots the Z figures onto the row, queues the P2 print
 * job, raises the discrepancy alert and signs the operator out of the terminal
 * (backend `ShiftService.close`). Drawing any of that ahead of the response
 * would mean a terminal that says "closed", a drawer figure nobody has written
 * and an operator still holding a live session if it then fails. So the screen
 * waits: the button spins, the figures stay exactly as they were, and only the
 * server's own report is rendered afterwards.
 *
 * None of these routes takes an `Idempotency-Key`, and none needs one
 * (backend `ShiftController`): opening twice is already 409
 * `SHIFT_ALREADY_OPEN`, closing twice is a 409 because there is no open shift
 * left to close, and the Z job is created inside the closing transaction, so a
 * retried close cannot print a second one.
 *
 * Every write invalidates `['shift', 'current']`, because all of them move the
 * expected drawer: an expense subtracts from it, an open replaces it, a close
 * ends it.
 */

import { useMutation, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { queryKeys } from '@/lib/query-keys';
import type { CreateExpenseInput, Expense, Shift, ShiftReport } from './schemas';

/** The X report and the petty cash that feeds it. */
function invalidateShift(client: QueryClient): void {
  void client.invalidateQueries({ queryKey: queryKeys.shift.current() });
  void client.invalidateQueries({ queryKey: queryKeys.expenses.all() });
}

/* ----------------------------------------------------------------- open */

/**
 * `POST /shifts` — 409 `SHIFT_ALREADY_OPEN` when this terminal already has one.
 *
 * A shift is unique per *terminal*, not per operator, so the refusal names the
 * till rather than the person: two people signing in on one counter share one
 * drawer, and that is the drawer the Z counts.
 */
export function useOpenShift() {
  const client = useQueryClient();
  return useMutation<Shift, unknown, { openingFloat: number }>({
    mutationFn: ({ openingFloat }) => api.post<Shift>('/shifts', { openingFloat }),
    onSuccess: () => invalidateShift(client),
  });
}

/* ---------------------------------------------------------------- close */

export type CloseShiftVariables = { countedCash: number; handoverNote?: string };

/**
 * `POST /shifts/current/close` — the Z, the alert, and the sign-out.
 *
 * The caller renders the returned report and then signs out; this hook does not
 * navigate. What it does do is drop the cached X the moment the shift is gone,
 * so a terminal that lingers for a frame cannot show a drawer belonging to a
 * shift that no longer exists.
 *
 * 403 when a cashier tries to close somebody else's shift ("a manager closes
 * it"), 409 when there is none open, 400 when the count is missing.
 */
export function useCloseShift() {
  const client = useQueryClient();
  return useMutation<ShiftReport, unknown, CloseShiftVariables>({
    mutationFn: ({ countedCash, handoverNote }) =>
      api.post<ShiftReport>('/shifts/current/close', {
        countedCash,
        handoverNote: handoverNote?.trim() || undefined,
      }),
    onSuccess: () => {
      client.removeQueries({ queryKey: queryKeys.shift.current() });
      client.removeQueries({ queryKey: queryKeys.expenses.all() });
    },
  });
}

/* ------------------------------------------------------------- the X print */

/**
 * `GET /shifts/current/x-report?print=true` — the interim read, on paper.
 *
 * A GET that has a side effect, because that is what the contract specifies:
 * the job is rendered and queued in the same transaction that computed the
 * figures, so the paper can never disagree with the response that produced it.
 * It is a mutation here for the same reason — it is an operator action with a
 * printer attached, not a read the cache may repeat.
 */
export function usePrintXReport() {
  const client = useQueryClient();
  return useMutation<ShiftReport, unknown, void>({
    mutationFn: () => api.get<ShiftReport>('/shifts/current/x-report', { query: { print: true } }),
    onSuccess: (report) => {
      // The response is a fresher reading than the cached one; keep it.
      client.setQueryData(queryKeys.shift.current(), report);
    },
  });
}

/* ---------------------------------------------------------- petty cash */

/**
 * `POST /expenses` (`?voucher=true` adds the P4 slip).
 *
 * Not optimistic either, and for a plainer reason than the close: the row
 * belongs to whichever shift is open on this terminal, and the server decides
 * that. A row drawn against a shift that turned out to be closed would be petty
 * cash charged to nobody's drawer.
 */
export function useRecordExpense() {
  const client = useQueryClient();
  return useMutation<Expense, unknown, CreateExpenseInput>({
    mutationFn: ({ description, category, amount, voucher }) =>
      api.post<Expense>(
        '/expenses',
        { description: description.trim(), category, amount },
        { query: { voucher } },
      ),
    onSuccess: () => invalidateShift(client),
  });
}
