'use client';

/**
 * `['sync']` — what the persistent chip on every screen says: *synced*,
 * *syncing*, or *offline since HH:MM* (design.md §1 "Global";
 * api-contract.md, "Live updates & sync").
 *
 * The chip is about the **cloud mirror**, never about the venue. Offline here
 * means the outbox has not reached the cloud; the terminal is talking to the
 * box in the same room and everything on it still works
 * (frontend/ARCHITECTURE.md §5.8). That is why the copy is a quiet note and
 * not an error state.
 *
 * `GET /sync/status` fills the key on mount, the SSE `sync-status` event keeps
 * it live, and the 10 s fallback in `lib/sse.ts` re-reads it whenever the
 * stream is down — so the chip is the one part of the shell that is honest
 * about its own liveness.
 */

import { useQuery } from '@tanstack/react-query';
import { api, type Schemas } from '@/lib/api';
import { queryKeys } from '@/lib/query-keys';
import type { SyncState } from '@/components/domain/sync-chip';

export type SyncStatus = Schemas['SyncStatus'];

/** The server's own spelling (`SyncState` on the backend). */
export const SERVER_SYNC_STATES = ['SYNCED', 'SYNCING', 'OFFLINE'] as const;
export type ServerSyncState = (typeof SERVER_SYNC_STATES)[number];

export function syncStatusQueryOptions() {
  return {
    queryKey: queryKeys.sync.status(),
    queryFn: () => api.get<SyncStatus>('/sync/status'),
    // The SSE event is the live source; this is the floor under it, and the
    // fallback poller re-reads it on the contract's cadence.
    staleTime: 10_000,
  };
}

/**
 * Server state → chip state.
 *
 * Two cases are the client's own, not the server's:
 *
 * - **unreachable** — the read itself failed, so the venue box is not
 *   answering. There is nothing to say about the cloud when we cannot even ask
 *   about it, and "offline" is the honest chip.
 * - **nothing yet** — the first read is still in flight. It shows *syncing*
 *   rather than defaulting to *synced*, because claiming a clean mirror we
 *   have not confirmed is the one wrong answer here.
 */
export function syncChipState(
  status: SyncStatus | undefined,
  options: { unreachable?: boolean } = {},
): SyncState {
  if (options.unreachable) return 'offline';
  const state = status?.state;
  switch (state) {
    case 'SYNCED':
      return 'synced';
    case 'SYNCING':
      return 'syncing';
    case 'OFFLINE':
      return 'offline';
    default:
      // Either no answer yet, or a state a newer backend invented: say we are
      // working on it rather than that everything is fine.
      return 'syncing';
  }
}

export type SyncChipStatus = {
  state: SyncState;
  /** The "offline since HH:MM" timestamp — absent until something has pushed. */
  lastSyncedAt: string | null;
  pendingOps: number;
};

/**
 * What the chip renders. `lastSyncedAt` survives an outage because the cached
 * answer does: the last time the cloud was reached is exactly what the
 * operator needs while it is unreachable.
 */
export function useSyncStatus(options: { enabled?: boolean } = {}): SyncChipStatus {
  const enabled = options.enabled ?? true;
  const query = useQuery({ ...syncStatusQueryOptions(), enabled });

  return {
    state: syncChipState(query.data, { unreachable: enabled && query.isError }),
    lastSyncedAt: query.data?.lastSyncedAt ?? null,
    pendingOps: query.data?.pendingOps ?? 0,
  };
}
