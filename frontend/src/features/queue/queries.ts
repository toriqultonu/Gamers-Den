'use client';

/**
 * Play-queue reads: `['queue']` — "who plays next" on the Floor rail
 * (docs/bookings.md §3, api-contract.md "Play queue").
 *
 * `GET /play-queue` answers with every WAITING token in counter order plus
 * today's SEATED ones as history; the rail shows the waiting ones. Waiting
 * tokens are deliberately **not** filtered to today: one issued yesterday and
 * never seated keeps its place and carries its own `tokenDate`, which is what
 * the TokenBadge prints beside it so two "#04"s a day apart cannot be confused
 * (frontend/ARCHITECTURE.md §5.12).
 *
 * The SSE `queue-update` event writes this exact key, so a ticket sold at the
 * POS appears on the floor without the rail asking for it (lib/sse.ts).
 */

import { useQuery } from '@tanstack/react-query';
import { api, type Schemas } from '@/lib/api';
import { queryKeys } from '@/lib/query-keys';
import { waitingInTokenOrder } from './schemas';

export type QueueEntry = Schemas['QueueEntry'];

export function playQueueQueryOptions() {
  return {
    queryKey: queryKeys.queue.all(),
    queryFn: () => api.get<QueueEntry[]>('/play-queue'),
  };
}

export function usePlayQueue(options: { enabled?: boolean } = {}) {
  return useQuery({ ...playQueueQueryOptions(), enabled: options.enabled ?? true });
}

/** The rail's rows: WAITING only, in token order across days. */
export function waitingEntries(entries: QueueEntry[] | undefined): QueueEntry[] {
  return waitingInTokenOrder(entries ?? []);
}
