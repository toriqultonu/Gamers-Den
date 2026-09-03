'use client';

/**
 * QueueRail / QueueRow — docs/design.md §2 and §1 (S3): WAITING entries in
 * token order, "No one waiting" as the empty state, and a seat action that is
 * disabled when there is no free console of the entry's type.
 *
 * The rail is "who plays next", not a turn order with teeth: staff may seat
 * **any** waiting token, because the customer standing at the counter chooses
 * (docs/bookings.md §3). So every row carries its own action rather than the
 * head of the queue owning one.
 *
 * The type rule is the one with teeth. A PS5 ticket cannot be seated on a PS4
 * — the server answers `CONSOLE_TYPE_MISMATCH` — so the row greys its action
 * out and says why before the operator finds out the hard way. The server
 * still has the last word; this only keeps the button honest.
 *
 * Tokens are queue identity, not payment proof, and the counter restarts at
 * venue midnight: a token from an earlier day keeps working and shows its
 * issue date (frontend/ARCHITECTURE.md §5.12).
 */

import { cn } from '@/components/ui/cn';
import { Button } from '@/components/ui/button';
import { TokenBadge } from '@/components/ui/token-badge';
import { formatBlocks } from '@/components/ui/time-stepper';
import { consoleLabel } from './station-card';
import type { QueueEntry } from '@/features/queue/queries';
import type { Station } from '@/features/sessions/schemas';
import { freeStationsOfType } from '@/features/sessions/queries';

/** design.md §2: "waiting, called". A row being seated is the called one. */
export const QUEUE_ROW_STATES = ['waiting', 'called'] as const;
export type QueueRowState = (typeof QUEUE_ROW_STATES)[number];

export type QueueRowProps = {
  entry: QueueEntry;
  /** Free consoles of this entry's type — empty disables the seat action. */
  seatable: Station[];
  onSeat?: (entry: QueueEntry, stationId: number) => void;
  state?: QueueRowState;
  /** Today in venue time, so a token from yesterday shows its date. */
  today?: string;
  /** The floor is read-only (a failed load, a 403) — the action is off. */
  disabled?: boolean;
};

export function QueueRow({
  entry,
  seatable,
  onSeat,
  state = 'waiting',
  today,
  disabled = false,
}: QueueRowProps) {
  // The selected console first when it qualifies, otherwise whichever is free:
  // the caller orders `seatable`, the row does not re-decide.
  const target = seatable[0];
  const canSeat = !disabled && target !== undefined && typeof entry.id === 'number';

  return (
    <li
      data-testid="queue-row"
      data-entry-id={entry.id}
      data-state={state}
      className={cn(
        'flex items-center gap-3 border-2 border-text px-3.5 py-2.5',
        state === 'called' && 'opacity-60',
      )}
    >
      <TokenBadge token={entry.tokenNo ?? 0} issuedOn={entry.tokenDate} today={today} />
      <div className="min-w-0">
        <div className="font-heading text-[14px] font-extrabold">
          {entry.playerName || 'Walk-in guest'}
        </div>
        <div className="text-[11px] opacity-60">
          {`${consoleLabel(entry.consoleType)} · ${formatBlocks(entry.blocks ?? 1)} · prepaid`}
        </div>
      </div>
      <Button
        variant="secondary"
        size="sm"
        className="ml-auto"
        disabled={!canSeat}
        loading={state === 'called'}
        title={target ? `Seat on ${target.name}` : 'No free console of that type right now.'}
        onClick={() => {
          if (!canSeat || target?.id === undefined) return;
          onSeat?.(entry, target.id);
        }}
      >
        {target ? `Seat on ${target.name}` : 'No free console'}
      </Button>
    </li>
  );
}

export type QueueRailProps = {
  /** WAITING entries, already in token order (`waitingEntries`). */
  entries: QueueEntry[];
  stations: Station[] | undefined;
  onSeat?: (entry: QueueEntry, stationId: number) => void;
  /** The entry whose seat call is in flight — its row reads `called`. */
  seatingEntryId?: number | null;
  /** Prefer this console when it is free and of the right type. */
  preferStationId?: number | null;
  today?: string;
  disabled?: boolean;
  className?: string;
};

export function QueueRail({
  entries,
  stations,
  onSeat,
  seatingEntryId = null,
  preferStationId = null,
  today,
  disabled = false,
  className,
}: QueueRailProps) {
  return (
    <section data-testid="queue-rail" className={cn('flex flex-col gap-2.5', className)}>
      <div className="flex items-baseline gap-3">
        <h2 className="type-label opacity-55">Play queue — up next</h2>
        <p className="text-[11px] opacity-50">
          Prepaid play tickets · token numbers reset at venue midnight
        </p>
      </div>

      {entries.length === 0 ? (
        <p data-testid="queue-empty" className="text-body opacity-60">
          No one waiting
        </p>
      ) : (
        <ul className="flex flex-wrap gap-2.5">
          {entries.map((entry) => (
            <QueueRow
              key={entry.id}
              entry={entry}
              seatable={seatableFor(entry, stations, preferStationId)}
              onSeat={onSeat}
              state={seatingEntryId === entry.id ? 'called' : 'waiting'}
              today={today}
              disabled={disabled}
            />
          ))}
        </ul>
      )}
    </section>
  );
}

/**
 * The consoles this token may go on, the selected one first.
 *
 * Ordering rather than filtering: an operator who has a console selected means
 * that one, but the row stays usable when they have not selected anything.
 */
export function seatableFor(
  entry: QueueEntry,
  stations: Station[] | undefined,
  preferStationId?: number | null,
): Station[] {
  const free = freeStationsOfType(stations, entry.consoleType);
  if (preferStationId === null || preferStationId === undefined) return free;
  const preferred = free.filter((station) => station.id === preferStationId);
  if (preferred.length === 0) return free;
  return [...preferred, ...free.filter((station) => station.id !== preferStationId)];
}
