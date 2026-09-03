'use client';

/**
 * Tournament reads: `['tournaments']`, `['tournaments', id]`,
 * `['tournaments', id, 'finance']`.
 *
 * F04 needs the list for one pixel of it: the sidebar's LIVE mark on
 * Tournaments (design.md §1, S12 row — "Sidebar (badge LIVE)"). S12 itself,
 * the bracket and the finance rail are F11.
 */

import { useQuery } from '@tanstack/react-query';
import { api, type Schemas } from '@/lib/api';
import { queryKeys } from '@/lib/query-keys';

export type Tournament = Schemas['Tournament'];

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
