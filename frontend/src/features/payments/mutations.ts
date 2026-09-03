'use client';

/**
 * `POST /payments` — the settle. **Never optimistic**
 * (frontend/ARCHITECTURE.md §5.3).
 *
 * This is the one mutation in the app where moving first would be indefensible.
 * A settle is a single server transaction that writes the transaction snapshot
 * and its tenders, marks the blocks it paid for, decrements stock, moves the
 * loyalty ledgers, registers tournament entries, takes daily queue tokens and
 * queues the receipt — all of it, or none of it. Half of that is not knowable
 * from here (the token numbers, the transaction id, the print job), and the
 * other half the server can still refuse: `SPLIT_MISMATCH` when the tenders do
 * not equal what is owed, `WALLET_INSUFFICIENT` past the balance,
 * `PAYMENT_REF_REQUIRED` on an MFS row with no TrxID, `TOURNAMENT_FULL` on an
 * entry that sold out while the bill was open.
 *
 * So nothing is written locally until the response lands. On failure the bill
 * is exactly as it was — the cart lines, the entries, the tickets, the attached
 * member, the redemption and the typed tender amounts all survive, and the
 * screen renders the notice above them (§4.4: an error never destroys entered
 * data). There is no rollback here because there was no roll forward.
 *
 * **Idempotency.** `lib/api.ts` attaches the key, one per *intent*: settling
 * this bill. A timeout, a 503 or a dropped network reuses it, so the server
 * replays its stored answer — the same `transactionId`, the same `printJobId`,
 * the same tokens — rather than charging twice. The key is released on success
 * and, server-side, on any refusal (only 2xx responses are stored), so an
 * operator who fixes a split and settles again is not fighting a stale key.
 */

import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { queryKeys } from '@/lib/query-keys';
import { settleBody, type SettleBodyInput, type SettleResult } from './schemas';

export type SettleInput = SettleBodyInput & {
  /**
   * The operator action this settle *is* — `settle:station:41`. Retries of the
   * same intent reuse one `Idempotency-Key`; a different bill is a different
   * intent and gets its own.
   */
  intent: string;
  /** The member on the bill, so their points and wallet are re-read after. */
  memberId?: number | null;
};

export function useSettle() {
  const client = useQueryClient();

  return useMutation<SettleResult, unknown, SettleInput>({
    mutationFn: (input) =>
      api.post<SettleResult>('/payments', settleBody(input), { intent: input.intent }),

    // Everything a settle touched, re-read from the server. The bill is the
    // important one: paid blocks stop being billable while the session keeps
    // running, so the panel must not go on showing what was just paid for.
    onSuccess: (_result, input) => {
      void client.invalidateQueries({ queryKey: queryKeys.items.all() });
      void client.invalidateQueries({ queryKey: queryKeys.stations.all() });
      void client.invalidateQueries({ queryKey: queryKeys.sessions.all() });
      if (typeof input.sessionId === 'number') {
        void client.invalidateQueries({ queryKey: queryKeys.sessions.bill(input.sessionId) });
      }
      // Tickets took daily tokens and entered the queue; entries filled slots
      // and may have drawn a bracket.
      if ((input.tickets ?? []).length > 0) {
        void client.invalidateQueries({ queryKey: queryKeys.queue.all() });
      }
      if ((input.entries ?? []).length > 0) {
        void client.invalidateQueries({ queryKey: queryKeys.tournaments.all() });
      }
      if (typeof input.memberId === 'number') {
        void client.invalidateQueries({ queryKey: queryKeys.members.detail(input.memberId) });
      }
      // The takings moved, so the open shift's X-report did too.
      void client.invalidateQueries({ queryKey: queryKeys.shift.current() });
    },
  });
}
