'use client';

/**
 * Tournament reads: `['tournaments']`, `['tournaments', 'history']`,
 * `['tournaments', id]`, `['tournaments', id, 'matches']`,
 * `['tournaments', id, 'finance']`.
 *
 * F04 needed the first of them for one pixel — the sidebar's LIVE mark. S12
 * needs the rest (F11), and one of them is a permission boundary rather than a
 * fetch: **the finance read is Manager+ and is never mounted for a cashier**
 * (docs/tournaments.md §1 and §6 — "403 for a cashier token, and never
 * embedded in a shared payload"). Not-mounting it is the cosmetic half; the
 * endpoint 403s regardless, which is the half that counts.
 */

import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { queryKeys } from '@/lib/query-keys';
import type {
  MatchBoard,
  Tournament,
  TournamentDetail,
  TournamentFinance,
} from './schemas';

export type { Tournament, TournamentDetail, TournamentFinance, MatchBoard };

/** `GET /tournaments` — events still selling or being played, soonest first. */
export function tournamentsQueryOptions() {
  return {
    queryKey: queryKeys.tournaments.all(),
    queryFn: () => api.get<Tournament[]>('/tournaments'),
    staleTime: 30_000,
  };
}

export function useTournaments(options: { enabled?: boolean } = {}) {
  return useQuery({ ...tournamentsQueryOptions(), enabled: options.enabled ?? true });
}

/** True while any event is being played — what the sidebar's LIVE mark means. */
export function hasLiveTournament(tournaments: Tournament[] | undefined): boolean {
  return (tournaments ?? []).some((tournament) => tournament.status === 'LIVE');
}

/**
 * `GET /tournaments/history` — the History tab: finished and called-off
 * events, most recent first, with winners, prizes and entry counts.
 */
export function tournamentHistoryQueryOptions() {
  return {
    queryKey: queryKeys.tournaments.history(),
    queryFn: () => api.get<Tournament[]>('/tournaments/history'),
    staleTime: 60_000,
  };
}

export function useTournamentHistory(options: { enabled?: boolean } = {}) {
  return useQuery({ ...tournamentHistoryQueryOptions(), enabled: options.enabled ?? true });
}

/**
 * `GET /tournaments/{id}` — the event with its entries, blocked consoles and
 * bracket.
 *
 * The bracket is empty until the draw, and that emptiness is the screen's
 * switch: before it, S12 shows the registered-player list; after it, the
 * columns. Every started match carries its own `remainingSeconds` computed
 * from the server clock, and `tournament-update` writes this key directly
 * (lib/sse.ts) — which is how a +5 min extend re-bases every countdown at
 * once.
 */
export function tournamentQueryOptions(id: number) {
  return {
    queryKey: queryKeys.tournaments.detail(id),
    queryFn: () => api.get<TournamentDetail>(`/tournaments/${id}`),
  };
}

export function useTournamentDetail(id: number | null | undefined) {
  return useQuery({
    ...tournamentQueryOptions(id ?? 0),
    enabled: typeof id === 'number' && id > 0,
  });
}

/**
 * `GET /tournaments/{id}/matches` — the match board, with console availability.
 *
 * The bracket already comes with the detail; what only this read carries is
 * why each allocated console is or is not free ("Allocated console busy with a
 * walk-in session"), which is exactly what the start button has to say when it
 * is disabled.
 */
export function matchBoardQueryOptions(id: number, pending = false) {
  return {
    queryKey: queryKeys.tournaments.board(id),
    queryFn: () =>
      api.get<MatchBoard>(`/tournaments/${id}/matches`, {
        query: pending ? { pending: true } : undefined,
      }),
  };
}

export function useMatchBoard(
  id: number | null | undefined,
  options: { enabled?: boolean; pending?: boolean } = {},
) {
  return useQuery({
    ...matchBoardQueryOptions(id ?? 0, options.pending ?? false),
    enabled: (options.enabled ?? true) && typeof id === 'number' && id > 0,
  });
}

/**
 * `GET /tournaments/{id}/finance` — Manager+ only (docs/tournaments.md §6).
 *
 * `enabled` is the caller's role check: S12 passes `canManage`, so a cashier's
 * terminal never issues the request at all. The 403 is still the authority —
 * this only keeps a guaranteed refusal off the wire.
 */
export function financeQueryOptions(id: number) {
  return {
    queryKey: queryKeys.tournaments.finance(id),
    queryFn: () => api.get<TournamentFinance>(`/tournaments/${id}/finance`),
  };
}

export function useTournamentFinance(
  id: number | null | undefined,
  options: { enabled?: boolean } = {},
) {
  return useQuery({
    ...financeQueryOptions(id ?? 0),
    enabled: (options.enabled ?? false) && typeof id === 'number' && id > 0,
  });
}
