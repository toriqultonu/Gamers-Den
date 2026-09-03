'use client';

/**
 * MatchBoard — docs/tournaments.md §4/§8: the cashier's job board. Every match
 * with two players and no winner yet, each with **start** and **+5 min**.
 *
 * Two things it refuses to guess:
 *
 *  - **Which console a match will take.** The server picks the first allocated
 *    console that is neither hosting an unfinished match nor busy with a
 *    walk-in session (§4). The board only *reports* that choice — "Next free
 *    console: PS5-1" — from the availability the same read carries, so the
 *    disabled start button explains itself ("Allocated console busy with a
 *    walk-in session") instead of firing a 409 `NO_FREE_CONSOLE` to find out.
 *  - **How much time a match has left.** The countdown ticks from the server's
 *    `remainingSeconds`; +5 min re-bases it off the next read (§5.2), never by
 *    adding minutes to a local clock.
 *
 * Starting and extending are ordinary execution — any role (§1) — and neither
 * is optimistic: a refused start must not leave a match looking as though it
 * were on a console.
 */

import { useState } from 'react';
import { AlertTriangle } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { errorNotice } from '@/lib/api';
import { useCountdown } from '@/lib/use-countdown';
import { useExtendMatch, useStartMatch } from '@/features/tournaments/mutations';
import {
  boardNote,
  boardStatus,
  bracketRounds,
  matchClockSnapshot,
  matchLabel,
  nextFreeConsole,
  pendingMatches,
  type ConsoleAvailability,
  type TournamentMatch,
} from '@/features/tournaments/schemas';

/** The one extend the board offers — design.md S12 row: "match board (start / +5 min)". */
export const EXTEND_MINUTES = 5;

export type MatchBoardProps = {
  tournamentId: number;
  matches: TournamentMatch[] | undefined;
  consoles: ConsoleAvailability[] | undefined;
  /** When the payload landed, in local ms — the row clocks' `asOf`. */
  receivedAt?: number;
  className?: string;
};

export function MatchBoard({
  tournamentId,
  matches,
  consoles,
  receivedAt,
  className,
}: MatchBoardProps) {
  const [notice, setNotice] = useState<string | null>(null);
  const start = useStartMatch();
  const extend = useExtendMatch();

  const rows = pendingMatches(matches);
  if (rows.length === 0) return null;

  const roundOf = new Map<number, string>();
  for (const round of bracketRounds(matches)) {
    for (const match of round.matches) {
      if (typeof match.id === 'number') roundOf.set(match.id, round.label);
    }
  }

  const free = nextFreeConsole(consoles);
  const landedAt = receivedAt ?? Date.now();

  return (
    <section
      data-testid="match-board"
      className={`flex flex-col gap-2.5 border-t-2 border-divider pt-3.5 ${className ?? ''}`}
    >
      <h3 className="type-label opacity-55">Match board</h3>
      <p className="text-[12px] opacity-60">{boardNote(consoles)}</p>

      {notice ? (
        <p
          role="alert"
          data-testid="match-board-notice"
          className="flex items-start gap-2 border-2 border-accent px-3 py-2 text-body text-accent-strong"
        >
          <AlertTriangle aria-hidden="true" className="mt-0.5 size-4 shrink-0" strokeWidth={2} />
          {notice}
        </p>
      ) : null}

      {rows.map((match) => (
        <BoardRow
          key={match.id}
          match={match}
          roundName={roundOf.get(match.id ?? -1) ?? 'Match'}
          consoles={consoles}
          receivedAt={landedAt}
          // A console is free, and nothing else on this board is mid-start.
          startable={Boolean(free) && !start.isPending}
          busy={start.isPending || extend.isPending}
          onStart={() => {
            if (typeof match.id !== 'number') return;
            setNotice(null);
            start.mutate(
              { tournamentId, matchId: match.id },
              { onError: (error) => setNotice(errorNotice(error, 'The match was not started.')) },
            );
          }}
          onExtend={() => {
            if (typeof match.id !== 'number') return;
            setNotice(null);
            extend.mutate(
              { tournamentId, matchId: match.id, minutes: EXTEND_MINUTES },
              { onError: (error) => setNotice(errorNotice(error, 'The time was not added.')) },
            );
          }}
        />
      ))}
    </section>
  );
}

function BoardRow({
  match,
  roundName,
  consoles,
  receivedAt,
  startable,
  busy,
  onStart,
  onExtend,
}: {
  match: TournamentMatch;
  roundName: string;
  consoles: ConsoleAvailability[] | undefined;
  receivedAt: number;
  startable: boolean;
  busy: boolean;
  onStart: () => void;
  onExtend: () => void;
}) {
  const seconds = useCountdown(matchClockSnapshot(match, receivedAt));
  const started = Boolean(match.startedAt);

  return (
    <div
      data-testid="match-board-row"
      data-match={match.id}
      data-started={started}
      className="flex items-center gap-3 border-2 border-divider px-3 py-2.5"
    >
      <div className="min-w-0 flex-1">
        <p className="font-heading text-[14px] font-extrabold">{matchLabel(match, roundName)}</p>
        <p data-testid="match-board-status" className="text-[11px] tabular opacity-60">
          {boardStatus(match, consoles, seconds)}
        </p>
      </div>

      {started ? (
        <Button variant="secondary" disabled={busy} onClick={onExtend}>
          {`+${EXTEND_MINUTES} min`}
        </Button>
      ) : null}

      <Button
        variant="primary"
        data-testid="start-match"
        disabled={started || !startable || busy}
        onClick={onStart}
      >
        {started ? 'In play' : 'Start match'}
      </Button>
    </div>
  );
}
