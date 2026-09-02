/**
 * TanStack Query configuration — the one place server state is cached.
 *
 * Two defaults carry weight in a venue:
 *
 * - **Retries are for transport, never for judgement.** A 4xx is the server
 *   telling us something true (`SESSION_HAS_BALANCE`, `CANCEL_CUTOFF_PASSED`) —
 *   re-asking cannot change the answer and only delays the notice. Network and
 *   5xx failures are retried, because the venue's own box briefly going away is
 *   an ordinary Tuesday.
 * - **Mutations never retry automatically.** Money and print calls are the
 *   idempotent ones (`lib/api.ts`), and a retry there is an operator decision —
 *   with the same `Idempotency-Key`, which is what makes it safe.
 *
 * `refetchOnReconnect` is on because SSE is the live channel and this is its
 * safety net (§5.2, F05 adds the 10 s polling fallback).
 */

import { QueryClient, isServer } from '@tanstack/react-query';
import { isApiError } from './api';

/** Retry transport failures only — never a decision the server already made. */
export function shouldRetryQuery(failureCount: number, error: unknown): boolean {
  if (failureCount >= 2) return false;
  if (!isApiError(error)) return true;
  if (error.code === 'NETWORK_ERROR') return true;
  return error.status >= 500;
}

export function makeQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        // Long enough that a screen switch does not re-fetch the floor; short
        // enough that a missed SSE frame self-heals within a few seconds.
        staleTime: 10_000,
        gcTime: 5 * 60_000,
        retry: shouldRetryQuery,
        refetchOnReconnect: true,
        // The floor terminal is never out of date because someone alt-tabbed;
        // SSE keeps it live, so focus refetching is just noise.
        refetchOnWindowFocus: false,
      },
      mutations: {
        retry: false,
      },
    },
  });
}

let browserQueryClient: QueryClient | undefined;

/**
 * One client per browser tab; a fresh one per server render so prefetched data
 * can never leak between requests (menu/stations/settings are prefetched into
 * the hydration boundary — §5.1).
 */
export function getQueryClient(): QueryClient {
  if (isServer) return makeQueryClient();
  browserQueryClient ??= makeQueryClient();
  return browserQueryClient;
}
