/**
 * Tournament shapes and the pure rules S12 draws with — docs/tournaments.md
 * §3 (bracket), §4 (match execution), §6 (finance), §8 (UI structure).
 *
 * Everything here is a function of the server's own payload. The bracket is
 * **not** assembled on the client: `GET /tournaments/{id}` returns every match
 * with its round, slot, players and `next_match_id` link already resolved, so
 * what these helpers do is group it into columns, name the rounds and decide
 * which row is clickable. A bracket drawn from a cap and a player list would
 * be a second implementation of the draw, and the two would disagree the first
 * time a bye moved.
 *
 * The one arithmetic claim worth stating: `max_players` is a power of two, so
 * a bracket has exactly **N−1 matches and no byes** (§3). That is what
 * {@link expectedMatchCount} says and what the screen renders.
 */

import { z } from 'zod';
import type { Schemas } from '@/lib/api';
import { formatBDT } from '@/lib/money';
import { formatCountdown, serverOffsetMs, type ClockSnapshot } from '@/lib/time';

export type Tournament = Schemas['Tournament'];
export type TournamentDetail = Schemas['TournamentDetail'];
export type TournamentEntry = Schemas['TournamentEntry'];
export type TournamentMatch = Schemas['TournamentMatch'];
export type TournamentFinance = Schemas['TournamentFinance'];
export type TournamentCancellation = Schemas['TournamentCancellation'];
export type MatchBoard = Schemas['MatchBoard'];
export type MatchDecision = Schemas['MatchDecision'];
export type ConsoleAvailability = Schemas['ConsoleAvailability'];

/* --------------------------------------------------------------- the event */

/** `tournaments.status` (DDL, docs/tournaments.md §2). */
export const TOURNAMENT_STATUSES = ['OPEN', 'LIVE', 'DONE', 'CANCELLED'] as const;
export type TournamentStatus = (typeof TOURNAMENT_STATUSES)[number];

/** The two tabs of S12 (§8). */
export const TOURNAMENT_TABS = ['live', 'history'] as const;
export type TournamentTab = (typeof TOURNAMENT_TABS)[number];

/** Powers of two only — the UI chips, and the whole reason there are no byes. */
export const TOURNAMENT_CAPS = [4, 8, 16, 32] as const;
export type TournamentCap = (typeof TOURNAMENT_CAPS)[number];

export const TOURNAMENT_CADENCES = ['WEEKLY', 'MONTHLY', 'ONE_OFF'] as const;
export type TournamentCadence = (typeof TOURNAMENT_CADENCES)[number];

export const CADENCE_LABELS: Record<TournamentCadence, string> = {
  WEEKLY: 'Weekly',
  MONTHLY: 'Monthly',
  ONE_OFF: 'One-off',
};

/** The status tag on a card and beside the title (prototype `tStatusTag`). */
export function tournamentStatusLabel(status: string | undefined): string {
  if (status === 'LIVE') return 'Live';
  if (status === 'OPEN') return 'Registration';
  if (status === 'CANCELLED') return 'Called off';
  return 'Done';
}

/** Which Tag variant carries a status (design.md §2). */
export function tournamentStatusTag(status: string | undefined): 'accent' | 'neutral' | 'outline' {
  if (status === 'LIVE') return 'accent';
  if (status === 'OPEN') return 'outline';
  return 'neutral';
}

/** "Everything still selling or being played" — what the left rail lists. */
export function isOpenOrLive(tournament: Pick<Tournament, 'status'>): boolean {
  return tournament.status === 'OPEN' || tournament.status === 'LIVE';
}

/** The card's second line: what it is and when. */
export function tournamentMeta(tournament: Tournament, when: string): string {
  const cadence = CADENCE_LABELS[(tournament.cadence ?? 'ONE_OFF') as TournamentCadence];
  return [tournament.game, cadence, when].filter(Boolean).join(' · ');
}

/** "3 / 8 registered" before the draw, "8 players" after it. */
export function slotsLabel(tournament: Tournament, drawn: boolean): string {
  const entries = tournament.entries ?? 0;
  const cap = tournament.maxPlayers ?? 0;
  return drawn ? `${entries} players` : `${entries} / ${cap} registered`;
}

/** The note under the registered-player list (§3: the last sale draws it). */
export function slotsNote(tournament: Tournament): string {
  const cap = tournament.maxPlayers ?? 0;
  const left = tournament.slotsLeft ?? Math.max(0, cap - (tournament.entries ?? 0));
  return `${left} of ${cap} slots open · the bracket generates itself when the last slot sells.`;
}

/* -------------------------------------------------------------- the bracket */

/**
 * Matches in a perfect bracket: **N−1**, no byes (docs/tournaments.md §3).
 * A cap that is not a power of two cannot be created — the API refuses it —
 * so this is a straight subtraction, not a ceiling.
 */
export function expectedMatchCount(maxPlayers: number | undefined): number {
  const cap = maxPlayers ?? 0;
  return cap > 0 ? cap - 1 : 0;
}

/** How many rounds a cap plays: log2(N). */
export function roundCount(maxPlayers: number | undefined): number {
  const cap = maxPlayers ?? 0;
  return cap > 1 ? Math.round(Math.log2(cap)) : 0;
}

/**
 * The column headings, first round first: `Round of 32 → … → Final`
 * (prototype `roundLabels`).
 */
export function roundLabels(rounds: number): string[] {
  return Array.from({ length: Math.max(0, rounds) }, (_, index) => roundLabel(rounds - index));
}

/** The name of a round counted back from the final (1 = Final). */
export function roundLabel(fromFinal: number): string {
  if (fromFinal <= 1) return 'Final';
  if (fromFinal === 2) return 'Semi-finals';
  if (fromFinal === 3) return 'Quarter-finals';
  return `Round of ${2 ** fromFinal}`;
}

export type BracketRound = {
  round: number;
  label: string;
  matches: TournamentMatch[];
};

/**
 * The server's flat match list as bracket columns.
 *
 * Round order is the server's `round` (1 = first), slot order its `slot`, and
 * the labels are named from the *back* so a 16-player event reads
 * "Round of 16 · Quarter-finals · Semi-finals · Final" whatever round number
 * the rows carry.
 */
export function bracketRounds(matches: TournamentMatch[] | undefined): BracketRound[] {
  const rows = matches ?? [];
  if (rows.length === 0) return [];

  const byRound = new Map<number, TournamentMatch[]>();
  for (const match of rows) {
    const round = match.round ?? 1;
    const bucket = byRound.get(round);
    if (bucket) bucket.push(match);
    else byRound.set(round, [match]);
  }

  const numbers = [...byRound.keys()].sort((a, b) => a - b);
  const labels = roundLabels(numbers.length);
  return numbers.map((round, index) => ({
    round,
    label: labels[index] ?? roundLabel(numbers.length - index),
    matches: [...(byRound.get(round) ?? [])].sort((a, b) => (a.slot ?? 0) - (b.slot ?? 0)),
  }));
}

/** Which player sits in a bracket row — `null` for a slot still being played for. */
export type MatchSide = 'A' | 'B';

export function playerOf(match: TournamentMatch, side: MatchSide): string | null {
  return (side === 'A' ? match.playerA : match.playerB) ?? null;
}

export function entryOf(match: TournamentMatch, side: MatchSide): number | null {
  return (side === 'A' ? match.entryA : match.entryB) ?? null;
}

/** The name a bracket row prints — "TBD" until the feeding match is decided. */
export const TBD = 'TBD';

export function playerLabel(match: TournamentMatch, side: MatchSide): string {
  return playerOf(match, side) ?? TBD;
}

/** True for the row that won — the red `W`, and its opponent dims. */
export function isWinnerSide(match: TournamentMatch, side: MatchSide): boolean {
  const winner = match.winnerEntryId;
  return typeof winner === 'number' && winner === entryOf(match, side);
}

export const MATCH_STATES = ['WAITING', 'READY', 'LIVE', 'TIME_UP', 'DONE'] as const;
export type MatchState = (typeof MATCH_STATES)[number];

/**
 * What a match is doing, by the server's own fields.
 *
 * `TIME_UP` is the server's `timeUp` at fetch; the tile and the board refine
 * it every second from the ticked countdown ({@link timeIsUp}) so a match that
 * runs out while the screen is open turns accent without a round trip.
 */
export function matchState(match: TournamentMatch): MatchState {
  if (typeof match.winnerEntryId === 'number') return 'DONE';
  if (match.startedAt) return match.timeUp ? 'TIME_UP' : 'LIVE';
  if (typeof match.entryA === 'number' && typeof match.entryB === 'number') return 'READY';
  return 'WAITING';
}

/** A started match whose countdown has reached zero, ticked or as fetched. */
export function timeIsUp(match: TournamentMatch, seconds: number | null | undefined): boolean {
  if (!match.startedAt || typeof match.winnerEntryId === 'number') return false;
  if (typeof seconds === 'number') return seconds <= 0;
  return Boolean(match.timeUp);
}

/**
 * Whether this row may be clicked to record a winner (docs/tournaments.md §1).
 *
 * A **started** match is execution — any role decides it. A match nobody
 * started is a ruling, and needs Manager+; a cashier clicking it would only
 * earn the 403 envelope, so the chip is not offered.
 */
export function canDecide(match: TournamentMatch, canManage: boolean): boolean {
  if (matchState(match) === 'DONE' || matchState(match) === 'WAITING') return false;
  return Boolean(match.startedAt) || canManage;
}

/** The bracket tag: `PS5-1 · 12:04`, `PS5-1 · time up`, or "Up next". */
export function matchTag(
  match: TournamentMatch,
  seconds: number | null | undefined,
  upNext = false,
): string | null {
  if (match.startedAt && typeof match.winnerEntryId !== 'number') {
    const console = match.stationName ?? 'console';
    return timeIsUp(match, seconds)
      ? `${console} · time up`
      : `${console} · ${formatCountdown(Math.max(0, seconds ?? 0))}`;
  }
  return upNext ? 'Up next' : null;
}

/**
 * The matches that would go on next — the first two decidable rows in drawing
 * order, tagged "Up next" so the counter knows what to start (prototype
 * `liveKey`/`nextKey`).
 */
export function upNextIds(matches: TournamentMatch[] | undefined, limit = 2): number[] {
  return (matches ?? [])
    .filter((match) => matchState(match) === 'READY')
    .slice(0, limit)
    .map((match) => match.id)
    .filter((id): id is number => typeof id === 'number');
}

/** Every match on a console right now — the "Now on «console»" tiles (§4). */
export function liveMatches(matches: TournamentMatch[] | undefined): TournamentMatch[] {
  return (matches ?? []).filter((match) => {
    const state = matchState(match);
    return state === 'LIVE' || state === 'TIME_UP';
  });
}

/** The board's rows: everything with two players and no winner yet (§4). */
export function pendingMatches(matches: TournamentMatch[] | undefined): TournamentMatch[] {
  return (matches ?? []).filter((match) => {
    const state = matchState(match);
    return state !== 'DONE' && state !== 'WAITING';
  });
}

/**
 * A match clock as the server handed it over.
 *
 * `TournamentMatch` carries `remainingSeconds` but not a timestamp of its own,
 * so the reading is dated by when the payload landed, converted to server time
 * — exactly what `stationClockSnapshot` does for a station summary
 * (frontend/ARCHITECTURE.md §5.2). An extend re-bases it because the next read
 * carries a bigger `remainingSeconds`, not because anything here adds minutes.
 */
export function matchClockSnapshot(
  match: TournamentMatch,
  receivedAt: number,
): ClockSnapshot | null {
  if (!match.startedAt || typeof match.remainingSeconds !== 'number') return null;
  return {
    remainingSeconds: match.remainingSeconds,
    asOf: receivedAt + serverOffsetMs(),
    running: typeof match.winnerEntryId !== 'number',
  };
}

/** "Quarter-finals · Rakib vs Nusrat" — the board row's own heading. */
export function matchLabel(match: TournamentMatch, roundName: string): string {
  return `${roundName} · ${playerLabel(match, 'A')} vs ${playerLabel(match, 'B')}`;
}

/* ---------------------------------------------------------- console supply */

/** The console `POST …/start` would take — the first allocated one that is free. */
export function nextFreeConsole(
  consoles: ConsoleAvailability[] | undefined,
): ConsoleAvailability | null {
  return (consoles ?? []).find((console) => console.available) ?? null;
}

export function freeConsoleCount(consoles: ConsoleAvailability[] | undefined): number {
  return (consoles ?? []).filter((console) => console.available).length;
}

/**
 * Why the start button is or is not live, in the operator's words.
 *
 * The server sends a `note` per console (docs/tournaments.md §4 — "Allocated
 * console busy with a walk-in session" is the case a bare 409 would not
 * explain), so its own sentence is preferred over anything invented here.
 */
export function consoleHint(consoles: ConsoleAvailability[] | undefined): string {
  const rows = consoles ?? [];
  if (rows.length === 0) return 'No consoles are blocked for this event — a manager allocates them.';
  const free = nextFreeConsole(rows);
  if (free) return `Next free console: ${free.stationName ?? 'allocated console'}`;
  const busy = rows.find((console) => console.note);
  return busy?.note ?? 'Waiting for a console to free up.';
}

/** The board row's status line — what this match is waiting on, or running on. */
export function boardStatus(
  match: TournamentMatch,
  consoles: ConsoleAvailability[] | undefined,
  seconds: number | null | undefined,
): string {
  if (!match.startedAt) return consoleHint(consoles);

  const console = match.stationName ?? 'console';
  const left = timeIsUp(match, seconds)
    ? 'time up — record the winner'
    : `${formatCountdown(Math.max(0, seconds ?? 0))} left`;
  const extra = match.extraMinutes ? ` · +${match.extraMinutes} min added` : '';
  return `On ${console} · ${left}${extra}`;
}

/** The note over the board: how many matches can run at once (prototype). */
export function boardNote(consoles: ConsoleAvailability[] | undefined): string {
  const count = (consoles ?? []).length;
  return `${count} console${count === 1 ? '' : 's'} allocated — that many matches run at once. Starting a match assigns a free console; record the winner by clicking their name in the bracket.`;
}

/* ------------------------------------------------------------------ finance */

/** "Opportunity cost = 7 matches × 20 min × ৳300/hr standard rate" (§6). */
export function financeFormula(finance: TournamentFinance): string {
  const matches = finance.matches ?? 0;
  const minutes = finance.matchDurationMin ?? 0;
  return `Opportunity cost = ${matches} matches × ${minutes} min × ${formatBDT(finance.avgHourlyRate ?? 0)}/hr standard rate`;
}

/**
 * The verdict line. The server sends its own (`verdict`); this is the fallback
 * phrasing docs/tournaments.md §6 specifies, negative case included.
 */
export function financeVerdict(finance: TournamentFinance): string {
  if (finance.verdict) return finance.verdict;
  const extra = finance.extraMargin ?? 0;
  return extra >= 0
    ? `This tournament generates ${formatBDT(extra)} extra compared to standard hourly rentals.`
    : `This tournament earns ${formatBDT(-extra)} less than standard hourly rentals would.`;
}

/* ------------------------------------------------------------- the forms */

export const cadenceSchema = z.enum(TOURNAMENT_CADENCES);

export const capSchema = z
  .int()
  .refine((value): value is TournamentCap => (TOURNAMENT_CAPS as readonly number[]).includes(value), {
    error: 'A bracket needs a power of two: 4, 8, 16 or 32.',
  });

/**
 * The arrange form (§8, manager rail). Mirrors `CreateTournamentRequest`, plus
 * the station chips — those are a second call (`PUT /{id}/blocks`), because the
 * create endpoint does not take them.
 */
export const createTournamentSchema = z.object({
  name: z.string().trim().min(1, 'Name the tournament.').max(80),
  game: z.string().trim().min(1, 'Which game is it?').max(60),
  cadence: cadenceSchema,
  scheduledAt: z.iso.datetime({ offset: true }),
  maxPlayers: capSchema,
  entryFee: z.int().nonnegative().max(100_000),
  prizePool: z.int().nonnegative().max(1_000_000),
  matchDurationMin: z.int().min(1, 'A match needs a length.').max(240),
  stationIds: z.array(z.int().positive()).default([]),
});

export type CreateTournamentInput = z.infer<typeof createTournamentSchema>;

export const cancelTournamentSchema = z.object({
  reason: z.string().trim().max(200).optional(),
});
