'use client';

/**
 * BracketView / MatchBox — docs/design.md §2 ("per tournaments.md"),
 * docs/tournaments.md §8: "bracket columns (Round of 16 → Final; Winner ✓
 * chips on decidable rows, red W on winners, dimmed losers)".
 *
 * One column per round, left to right, drawn from the server's own match rows
 * — round, slot and the `next_match_id` link are already resolved there, so
 * **propagation is a display of what the server did**, not a re-simulation of
 * it: the winner of a decided match shows up as `playerA`/`playerB` of the
 * match it advances into, and an undecided feeder leaves that side reading
 * `TBD`.
 *
 * A perfect bracket has exactly N−1 boxes and no byes (§3), which is what a
 * 4/8/16/32 cap buys and what this renders without special cases.
 *
 * The per-match tag is `console · mm:ss` and it ticks off the server clock
 * (§5.2): a +5 min extend arrives as a bigger `remainingSeconds` on the next
 * read and the tag re-bases, rather than anything here adding minutes locally.
 */

import { cn } from '@/components/ui/cn';
import { useCountdown } from '@/lib/use-countdown';
import {
  bracketRounds,
  canDecide,
  entryOf,
  isWinnerSide,
  matchClockSnapshot,
  matchTag,
  playerLabel,
  timeIsUp,
  upNextIds,
  type MatchSide,
  type TournamentMatch,
} from '@/features/tournaments/schemas';

export type BracketViewProps = {
  matches: TournamentMatch[] | undefined;
  /** Manager+ may also decide a match nobody started (docs/tournaments.md §1). */
  canManage: boolean;
  /** When the detail landed, in local ms — the match clocks' `asOf`. */
  receivedAt?: number;
  /** The match a winner is being recorded on right now. */
  decidingMatchId?: number | null;
  onDecide?: (match: TournamentMatch, winnerEntryId: number) => void;
  className?: string;
};

export function BracketView({
  matches,
  canManage,
  receivedAt,
  decidingMatchId = null,
  onDecide,
  className,
}: BracketViewProps) {
  const rounds = bracketRounds(matches);
  if (rounds.length === 0) return null;

  const upNext = new Set(upNextIds(matches));
  const landedAt = receivedAt ?? Date.now();

  return (
    <div
      data-testid="bracket"
      data-matches={(matches ?? []).length}
      className={cn('flex items-stretch gap-4 overflow-x-auto', className)}
    >
      {rounds.map((round) => (
        <div
          key={round.round}
          data-testid="bracket-round"
          data-round={round.round}
          className="flex min-w-[190px] flex-1 flex-col gap-2.5"
        >
          <p className="type-label opacity-55">{round.label}</p>
          <div className="flex flex-1 flex-col justify-around gap-3">
            {round.matches.map((match) => (
              <MatchBox
                key={match.id ?? `${round.round}-${match.slot}`}
                match={match}
                canManage={canManage}
                upNext={typeof match.id === 'number' && upNext.has(match.id)}
                receivedAt={landedAt}
                busy={decidingMatchId === match.id}
                onDecide={onDecide}
              />
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}

export type MatchBoxProps = {
  match: TournamentMatch;
  canManage: boolean;
  upNext?: boolean;
  receivedAt?: number;
  busy?: boolean;
  onDecide?: (match: TournamentMatch, winnerEntryId: number) => void;
};

/**
 * One box: the tag, then the two player rows.
 *
 * The box is accent-bordered while its match is on, plain while it waits, and
 * divider-grey once decided — the same three weights the prototype draws.
 */
export function MatchBox({
  match,
  canManage,
  upNext = false,
  receivedAt,
  busy = false,
  onDecide,
}: MatchBoxProps) {
  const seconds = useCountdown(matchClockSnapshot(match, receivedAt ?? Date.now()));
  const live = Boolean(match.startedAt) && typeof match.winnerEntryId !== 'number';
  const over = timeIsUp(match, seconds);
  const tag = matchTag(match, seconds, upNext);
  const decidable = canDecide(match, canManage) && !busy;

  return (
    <div
      data-testid="match-box"
      data-match={match.id}
      data-state={live ? (over ? 'time-up' : 'live') : match.winnerEntryId ? 'done' : 'pending'}
      className={cn(
        'flex flex-col gap-1.5 border-2 p-2.5',
        live ? 'border-accent bg-card' : match.winnerEntryId ? 'border-divider' : 'border-text',
      )}
    >
      {tag ? (
        <span
          data-testid="match-tag"
          className={cn(
            'self-start px-1.5 py-0.5 font-heading text-[9px] font-extrabold tracking-[0.12em] uppercase tabular',
            live ? 'bg-accent text-on-accent' : 'border border-accent text-accent-strong',
          )}
        >
          {tag}
        </span>
      ) : null}

      {(['A', 'B'] as MatchSide[]).map((side) => (
        <MatchRow
          key={side}
          match={match}
          side={side}
          decidable={decidable}
          busy={busy}
          onDecide={onDecide}
        />
      ))}
    </div>
  );
}

function MatchRow({
  match,
  side,
  decidable,
  busy,
  onDecide,
}: {
  match: TournamentMatch;
  side: MatchSide;
  decidable: boolean;
  busy: boolean;
  onDecide?: (match: TournamentMatch, winnerEntryId: number) => void;
}) {
  const name = playerLabel(match, side);
  const entryId = entryOf(match, side);
  const won = isWinnerSide(match, side);
  const lost = typeof match.winnerEntryId === 'number' && !won;
  const clickable = decidable && entryId !== null && Boolean(onDecide);

  const row = (
    <>
      <span className="truncate">{name}</span>
      {won ? (
        <span data-testid="match-winner-mark" className="font-heading text-[13px] font-extrabold text-accent">
          W
        </span>
      ) : clickable ? (
        <span className="border border-accent px-1.5 py-px font-heading text-[9px] font-extrabold tracking-[0.08em] whitespace-nowrap text-accent-strong uppercase">
          Winner ✓
        </span>
      ) : null}
    </>
  );

  const className = cn(
    'flex items-center justify-between gap-2 text-[13px]',
    won && 'font-heading font-extrabold',
    lost && 'opacity-40',
    entryId === null && 'opacity-35',
  );

  if (!clickable) {
    return (
      <span data-testid="match-row" data-side={side} className={className}>
        {row}
      </span>
    );
  }

  return (
    <button
      type="button"
      data-testid="match-row"
      data-side={side}
      disabled={busy}
      aria-label={`Record ${name} as the winner`}
      onClick={() => onDecide?.(match, entryId)}
      className={cn(
        className,
        'cursor-pointer text-left',
        'focus-visible:outline-2 focus-visible:outline-accent focus-visible:outline-offset-2',
        'disabled:cursor-progress',
      )}
    >
      {row}
    </button>
  );
}
