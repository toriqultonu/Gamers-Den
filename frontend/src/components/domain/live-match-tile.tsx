'use client';

/**
 * LiveMatchTile — docs/tournaments.md §4/§8: the "Now on «console»" tiles with
 * a big countdown and an **accent TIME UP state**.
 *
 * One tile per match currently on a console. The clock is the same server
 * reading the bracket tag, the match board and the Floor card tick from
 * (§5.2), so all four move together — including when a +5 min extend re-bases
 * them.
 *
 * TIME UP is not an error state: the match is over and the winner still has to
 * be recorded, which is why the tile inverts to accent rather than just going
 * red — it is the loudest thing on the screen until someone acts on it.
 */

import { cn } from '@/components/ui/cn';
import { formatCountdown } from '@/lib/time';
import { useCountdown } from '@/lib/use-countdown';
import {
  matchClockSnapshot,
  playerLabel,
  timeIsUp,
  type TournamentMatch,
} from '@/features/tournaments/schemas';

export type LiveMatchTileProps = {
  match: TournamentMatch;
  /** When the payload landed, in local ms — the clock's `asOf`. */
  receivedAt?: number;
  className?: string;
};

export function LiveMatchTile({ match, receivedAt, className }: LiveMatchTileProps) {
  const seconds = useCountdown(matchClockSnapshot(match, receivedAt ?? Date.now()));
  const over = timeIsUp(match, seconds);

  return (
    <div
      data-testid="live-match-tile"
      data-match={match.id}
      data-state={over ? 'time-up' : 'running'}
      className={cn(
        'flex min-w-[220px] flex-1 flex-col gap-0.5 border-2 p-4',
        over ? 'border-accent bg-accent text-on-accent' : 'border-text bg-card text-text',
        className,
      )}
    >
      <div className="flex items-baseline justify-between gap-2.5">
        <span className="type-label opacity-70">{`Now on ${match.stationName ?? 'console'}`}</span>
        <span className="truncate text-[12px] opacity-80">
          {`${playerLabel(match, 'A')} vs ${playerLabel(match, 'B')}`}
        </span>
      </div>
      <span
        data-testid="live-match-time"
        className="font-heading text-[26px] leading-none font-extrabold tracking-[-0.03em] tabular"
      >
        {over ? 'TIME UP' : formatCountdown(Math.max(0, seconds ?? 0))}
      </span>
    </div>
  );
}
