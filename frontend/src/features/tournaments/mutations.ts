'use client';

/**
 * Tournament writes — arrange, block consoles, cancel, draw, start, extend,
 * record a winner (docs/tournaments.md §3–§4).
 *
 * **None of them is optimistic** (frontend/ARCHITECTURE.md §5.3 names winner
 * recording explicitly). The reason is the same one every time: each of these
 * calls is a fact about a bracket other terminals are watching. A winner drawn
 * early and then refused would advance a player on this screen and nowhere
 * else; a start drawn early would claim a console the server may have given to
 * the match beside it. So the screen shows the failure banner design.md §1
 * (S12) asks for, and the bracket stays exactly as the server last described
 * it.
 *
 * None of these routes is on the `Idempotency-Key` list either — the guarded
 * tournament route is `POST /tournaments/{id}/entries`, which is a *sale* and
 * belongs to the POS settle path (F07/F08). Everything here is naturally
 * idempotent through its own 409 (a decided match refuses a second winner, a
 * started match refuses a second start).
 *
 * Every write ends by re-reading what it moved: the event, its board, the list
 * (a draw flips OPEN → LIVE, and the sidebar's LIVE mark with it) and
 * `['stations']`, because blocking, starting, cancelling and winning all change
 * what the Floor cards say about a console (docs/tournaments.md §4).
 */

import { useMutation, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { queryKeys } from '@/lib/query-keys';
import type {
  CreateTournamentInput,
  MatchDecision,
  TournamentCancellation,
  TournamentDetail,
  TournamentMatch,
} from './schemas';

/**
 * Everything a tournament write moves.
 *
 * The list because a status changed on it, the event and its board because the
 * bracket did, and the stations because a blocked console reads RESERVED on the
 * Floor and a started match puts a countdown on the card.
 */
function invalidateTournament(client: QueryClient, id: number | undefined): void {
  void client.invalidateQueries({ queryKey: queryKeys.tournaments.all() });
  if (typeof id === 'number') {
    void client.invalidateQueries({ queryKey: queryKeys.tournaments.detail(id) });
    void client.invalidateQueries({ queryKey: queryKeys.tournaments.board(id) });
    void client.invalidateQueries({ queryKey: queryKeys.tournaments.finance(id) });
  }
  void client.invalidateQueries({ queryKey: queryKeys.stations.all() });
}

/** A response that already describes the event: write it in, then re-read. */
function acceptDetail(client: QueryClient, detail: TournamentDetail): void {
  const id = detail.tournament?.id;
  if (typeof id === 'number') client.setQueryData(queryKeys.tournaments.detail(id), detail);
  invalidateTournament(client, id);
}

/* -------------------------------------------------------------- arrange */

/**
 * `POST /tournaments` (Manager+), then `PUT /tournaments/{id}/blocks` when the
 * form picked consoles.
 *
 * Two calls because the API has two: create does not take an allocation, and
 * the blocks endpoint replaces the whole set. They are sequenced rather than
 * fired together so the second one has an id to address — and a failure on the
 * second leaves a real tournament with no consoles yet, which the rail can fix
 * by touching the chips, rather than a half-written event.
 *
 * 409 `DUPLICATE_NAME` on a taken name.
 */
export function useCreateTournament() {
  const client = useQueryClient();

  return useMutation<TournamentDetail, unknown, CreateTournamentInput>({
    mutationFn: async (input) => {
      const created = await api.post<TournamentDetail>('/tournaments', {
        name: input.name,
        game: input.game,
        cadence: input.cadence,
        scheduledAt: input.scheduledAt,
        maxPlayers: input.maxPlayers,
        entryFee: input.entryFee,
        prizePool: input.prizePool,
        matchDurationMin: input.matchDurationMin,
      });

      const id = created.tournament?.id;
      if (typeof id !== 'number' || input.stationIds.length === 0) return created;
      return api.put<TournamentDetail>(`/tournaments/${id}/blocks`, {
        stationIds: input.stationIds,
      });
    },

    onSuccess: (detail) => acceptDetail(client, detail),
  });
}

/**
 * `PUT /tournaments/{id}/blocks` (Manager+) — replaces the whole allocation.
 *
 * While the event is OPEN or LIVE these consoles read RESERVED on the Floor and
 * refuse walk-in sessions with 409 `STATION_RESERVED`; an empty list releases
 * them (docs/tournaments.md §2).
 */
export function useSetStationBlocks() {
  const client = useQueryClient();

  return useMutation<TournamentDetail, unknown, { tournamentId: number; stationIds: number[] }>({
    mutationFn: ({ tournamentId, stationIds }) =>
      api.put<TournamentDetail>(`/tournaments/${tournamentId}/blocks`, { stationIds }),

    onSuccess: (detail) => acceptDetail(client, detail),
  });
}

/**
 * `POST /tournaments/{id}/bracket` (Manager+) — the undersubscribed draw.
 *
 * An event that fills is drawn by the sale that takes the last slot, in that
 * sale's own transaction (§3), so this button exists for the event that never
 * filled: the smallest power-of-two bracket that seats everyone who bought in,
 * byes advancing the earliest seeds. 409 `NOT_ENOUGH_PLAYERS` under two.
 */
export function useGenerateBracket() {
  const client = useQueryClient();

  return useMutation<TournamentDetail, unknown, { tournamentId: number }>({
    mutationFn: ({ tournamentId }) =>
      api.post<TournamentDetail>(`/tournaments/${tournamentId}/bracket`, undefined),

    onSuccess: (detail) => acceptDetail(client, detail),
  });
}

/**
 * `POST /tournaments/{id}/cancel` (Manager+) — call it off and refund everyone.
 *
 * One server transaction: CANCELLED, every console released, and a negative
 * refund transaction per originating sale posted to this terminal's open shift
 * — so the shift read moves too.
 */
export function useCancelTournament() {
  const client = useQueryClient();

  return useMutation<TournamentCancellation, unknown, { tournamentId: number; reason?: string }>({
    mutationFn: ({ tournamentId, reason }) =>
      api.post<TournamentCancellation>(`/tournaments/${tournamentId}/cancel`, {
        reason: reason?.trim() || undefined,
      }),

    onSuccess: (cancellation, { tournamentId }) => {
      invalidateTournament(client, cancellation.tournament?.id ?? tournamentId);
      void client.invalidateQueries({ queryKey: queryKeys.tournaments.history() });
      void client.invalidateQueries({ queryKey: queryKeys.shift.current() });
    },
  });
}

/* ------------------------------------------------------------ execution */

/**
 * `POST /tournaments/{id}/matches/{mid}/start` (any role) — put a match on a
 * console.
 *
 * The server takes the first allocated console that is neither hosting an
 * unfinished match nor busy with a walk-in session and stamps `started_at`;
 * the countdown runs from there. 409 `NO_FREE_CONSOLE` when every allocated
 * console is taken — the board already says which, so the banner explains
 * rather than surprises.
 */
export function useStartMatch() {
  const client = useQueryClient();

  return useMutation<TournamentMatch, unknown, { tournamentId: number; matchId: number }>({
    mutationFn: ({ tournamentId, matchId }) =>
      api.post<TournamentMatch>(`/tournaments/${tournamentId}/matches/${matchId}/start`, undefined),

    onSuccess: (_match, { tournamentId }) => invalidateTournament(client, tournamentId),
  });
}

/**
 * `POST /tournaments/{id}/matches/{mid}/extend` (any role) — add time.
 *
 * Minutes accumulate on the match, and **every countdown re-bases off the next
 * read**: the board row, the bracket tag, the "Now on" tile and the Floor card
 * all move together because they all tick from the same server
 * `remainingSeconds` (§5.2). Nothing here adds minutes to a local clock.
 */
export function useExtendMatch() {
  const client = useQueryClient();

  return useMutation<
    TournamentMatch,
    unknown,
    { tournamentId: number; matchId: number; minutes: number }
  >({
    mutationFn: ({ tournamentId, matchId, minutes }) =>
      api.post<TournamentMatch>(`/tournaments/${tournamentId}/matches/${matchId}/extend`, {
        minutes,
      }),

    onSuccess: (_match, { tournamentId }) => invalidateTournament(client, tournamentId),
  });
}

/**
 * `POST /tournaments/{id}/matches/{mid}/winner` — never optimistic (§5.3).
 *
 * Any role may decide a **started** match; a match nobody started is a ruling
 * and needs Manager+, which the cashier's bracket does not offer and the API
 * refuses with the 403 envelope either way. The response carries the whole
 * bracket back, plus `suggestedStationId` for the advanced player's next match
 * — winning the final makes the champion, turns the event DONE and releases
 * every console it held.
 */
export function useRecordWinner() {
  const client = useQueryClient();

  return useMutation<
    MatchDecision,
    unknown,
    { tournamentId: number; matchId: number; winnerEntryId: number }
  >({
    mutationFn: ({ tournamentId, matchId, winnerEntryId }) =>
      api.post<MatchDecision>(`/tournaments/${tournamentId}/matches/${matchId}/winner`, {
        winnerEntryId,
      }),

    onSuccess: (decision, { tournamentId }) => {
      const id = decision.tournament?.id ?? tournamentId;
      // The decision *is* the new detail — same fields, one round trip saved —
      // and the invalidation behind it still re-reads the board's consoles.
      client.setQueryData(queryKeys.tournaments.detail(id), {
        tournament: decision.tournament,
        entries: decision.entries,
        stationIds: decision.stationIds,
        bracket: decision.bracket,
      } satisfies TournamentDetail);
      invalidateTournament(client, id);
      if (decision.champion) {
        void client.invalidateQueries({ queryKey: queryKeys.tournaments.history() });
      }
    },
  });
}
