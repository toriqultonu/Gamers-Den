'use client';

/**
 * Session writes: start, blocks ±, clock, end (api-contract.md, "Sessions").
 *
 * Two rules from frontend/ARCHITECTURE.md §5.3 shape everything here:
 *
 * - **Block ± is optimistic.** It is the control an operator taps most, and a
 *   round-trip of latency on "+30 min" reads as a dead button. So the cached
 *   session and the station card move at once and the server reconciles —
 *   except when it refuses (`BLOCKS_CONSUMED`: that half hour has already been
 *   played or paid for), which rolls the cache back to exactly what it was and
 *   hands the panel a notice.
 * - **Seat is never optimistic.** Seating spends a prepaid token and creates
 *   real money-bearing rows; showing a session that may not exist is the one
 *   lie the floor cannot afford. Same for start, clock and end.
 *
 * The `+30 min` call is one of the guarded money routes, so it carries an
 * `Idempotency-Key` keyed to the operator's intent — a retried tap after a
 * timeout buys one block, not two (api-contract.md §1).
 */

import { useMutation, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { api, type Schemas } from '@/lib/api';
import { queryKeys } from '@/lib/query-keys';
import { BLOCK_SECONDS } from '@/lib/time';
import type { Station } from './queries';

export type Session = Schemas['Session'];

/** `POST /sessions` — a walk-in start, or a seat that loads prepaid blocks. */
export type CreateSessionInput = {
  stationId: number;
  memberId?: number;
  bookingId?: number;
  queueEntryId?: number;
};

/**
 * Everything the floor shows about this console changed: the card, the
 * session behind it, its bill, and — when a token was spent — the rail.
 */
function invalidateFloor(client: QueryClient, sessionId?: number): void {
  void client.invalidateQueries({ queryKey: queryKeys.stations.all() });
  void client.invalidateQueries({ queryKey: queryKeys.queue.all() });
  if (typeof sessionId === 'number') {
    // Prefix match — the detail and its bill both.
    void client.invalidateQueries({ queryKey: queryKeys.sessions.detail(sessionId) });
  } else {
    void client.invalidateQueries({ queryKey: queryKeys.sessions.all() });
  }
}

/**
 * Start a session on a free console. Not optimistic: `STATION_BUSY` and
 * `STATION_RESERVED` are real answers, and a card that pretends otherwise
 * sends staff to a console somebody else is already on.
 */
export function useStartSession() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: CreateSessionInput) => api.post<Session>('/sessions', input),
    onSuccess: (session) => invalidateFloor(client, session.id),
  });
}

/* ------------------------------------------------------------ blocks (±30) */

export type BlockChange = {
  sessionId: number;
  delta: 1 | -1;
};

/** What `onMutate` puts aside so a refusal can put the floor back. */
type BlocksRollback = {
  session: Session | undefined;
  stations: Station[] | undefined;
};

/**
 * `POST /sessions/{id}/blocks` — one 30-minute block, either way.
 *
 * The optimistic patch moves only what the client can work out for itself:
 * the block count and the clock. Money (`gamingDue`, `netOutstanding`) is the
 * server's arithmetic at the server's prices, so it is left alone and arrives
 * with the reconciling read — a bill that guesses is worse than a bill that
 * lands a beat late.
 */
export function useChangeBlocks() {
  const client = useQueryClient();

  return useMutation<Session, unknown, BlockChange, BlocksRollback>({
    mutationFn: ({ sessionId, delta }) =>
      api.post<Session>(
        `/sessions/${sessionId}/blocks`,
        { delta },
        // One intent per console *and direction*: a retry after a timeout
        // reuses the key, a fresh tap after a success mints a new one, and a
        // −30 following a failed +30 never reuses a key under a different
        // body — which the server answers with `IDEMPOTENCY_REPLAY`
        // (lib/api.ts, api-contract.md §1).
        { intent: `session-blocks:${sessionId}:${delta > 0 ? 'add' : 'remove'}` },
      ),

    async onMutate({ sessionId, delta }) {
      await client.cancelQueries({ queryKey: queryKeys.sessions.detail(sessionId) });
      await client.cancelQueries({ queryKey: queryKeys.stations.all() });

      const session = client.getQueryData<Session>(queryKeys.sessions.detail(sessionId));
      const stations = client.getQueryData<Station[]>(queryKeys.stations.all());

      if (session) {
        client.setQueryData<Session>(queryKeys.sessions.detail(sessionId), {
          ...session,
          blocks: (session.blocks ?? 0) + delta,
          remainingSeconds: (session.remainingSeconds ?? 0) + delta * BLOCK_SECONDS,
        });
      }

      if (stations) {
        client.setQueryData<Station[]>(
          queryKeys.stations.all(),
          stations.map((station) =>
            station.session?.id === sessionId
              ? {
                  ...station,
                  session: {
                    ...station.session,
                    blocks: (station.session.blocks ?? 0) + delta,
                    remainingSeconds: (station.session.remainingSeconds ?? 0) + delta * BLOCK_SECONDS,
                  },
                }
              : station,
          ),
        );
      }

      return { session, stations };
    },

    // `BLOCKS_CONSUMED` — the block was already played or paid for. The floor
    // goes back to exactly the reading it had; the panel renders the notice.
    onError(_error, { sessionId }, rollback) {
      if (!rollback) return;
      if (rollback.session) {
        client.setQueryData(queryKeys.sessions.detail(sessionId), rollback.session);
      }
      if (rollback.stations) {
        client.setQueryData(queryKeys.stations.all(), rollback.stations);
      }
    },

    onSettled(_session, _error, { sessionId }) {
      invalidateFloor(client, sessionId);
    },
  });
}

/* ------------------------------------------------------------------- clock */

export type ClockAction = 'START' | 'PAUSE' | 'RESUME';

/**
 * `POST /sessions/{id}/clock`. Not optimistic: `NO_BLOCKS` is how the server
 * says "buy time first", and a clock that starts and then jumps back is the
 * one thing a countdown must never do.
 */
export function useClockAction() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ sessionId, action }: { sessionId: number; action: ClockAction }) =>
      api.post<Session>(`/sessions/${sessionId}/clock`, { action }),
    onSuccess: (session, { sessionId }) => {
      client.setQueryData(queryKeys.sessions.detail(sessionId), session);
      invalidateFloor(client, sessionId);
    },
  });
}

/* --------------------------------------------------------------------- end */

/**
 * `POST /sessions/{id}/end` — refused with `SESSION_HAS_BALANCE` while the
 * **net** is unsettled (charges − prepaid). The panel disables the button on
 * the same reading, so the 409 is the backstop rather than the message.
 */
export function useEndSession() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (sessionId: number) => api.post<Session>(`/sessions/${sessionId}/end`),
    onSuccess: (_session, sessionId) => invalidateFloor(client, sessionId),
  });
}
