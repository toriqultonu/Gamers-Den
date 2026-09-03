'use client';

/**
 * StationCard — docs/design.md §2: eight variants (active · open · paused ·
 * locked · free · reserved · booked · maintenance), selected-outline and hover
 * states, props `station, selected, onSelect`.
 *
 * The eight variants are `Station.floorState` under design names, so the card
 * never works out what the floor is — it renders what the server said it is
 * (`features/sessions/schemas.ts`).
 *
 * Two of them carry facts from other modules:
 *
 * - **reserved** — a console a tournament holds. With a started match it shows
 *   the match countdown like any other session, the two players, and "match
 *   over" past zero; without one, "reserved · tournament"
 *   (docs/tournaments.md §4).
 * - **booked** — free, but holding a checked-in arrival: the token, the name
 *   and the prepaid length, which is the prompt the panel turns into a seat
 *   action (docs/bookings.md §2).
 *
 * The clock is the shared CountdownClock at its 52px card size, ticking from
 * the server reading — never the local wall clock.
 */

import { cva } from 'class-variance-authority';
import { cn } from '@/components/ui/cn';
import { formatToken } from '@/components/ui/token-badge';
import { CountdownClock } from './countdown-clock';
import { BLOCK_SECONDS } from '@/lib/time';
import { formatBlocks } from '@/components/ui/time-stepper';
import {
  stationClockSnapshot,
  stationVariant,
  type Station,
  type StationVariant,
} from '@/features/sessions/schemas';

const card = cva(
  [
    'flex min-h-[210px] w-full cursor-pointer flex-col gap-0.5 rounded-none border-2 p-5 text-left',
    'border-l-[10px] transition-colors',
    'focus-visible:outline-3 focus-visible:outline-accent focus-visible:outline-offset-3',
  ],
  {
    variants: {
      variant: {
        free: 'border-divider border-l-divider bg-transparent',
        open: 'border-text border-l-divider bg-transparent',
        active: 'border-text border-l-accent bg-card',
        paused: 'border-text border-l-divider bg-transparent',
        locked: 'border-text border-l-text bg-accent-100',
        reserved: 'border-accent border-l-accent bg-transparent',
        booked: 'border-text border-l-accent bg-transparent',
        maintenance: 'border-divider border-l-divider bg-transparent opacity-60',
      },
      selected: {
        true: 'outline-3 outline-accent outline-offset-3',
        false: '',
      },
    },
    defaultVariants: { variant: 'free', selected: false },
  },
);

const chip = cva('type-label ml-auto px-2 py-0.5 font-heading font-extrabold', {
  variants: {
    variant: {
      free: 'border border-divider text-text',
      open: 'bg-neutral-300 text-neutral-900',
      active: 'bg-accent text-on-accent',
      paused: 'bg-neutral-400 text-neutral-900',
      locked: 'bg-text text-bg',
      reserved: 'bg-accent-100 text-accent-800',
      booked: 'bg-accent-tint text-accent-800',
      maintenance: 'bg-neutral-400 text-neutral-900',
    },
  },
  defaultVariants: { variant: 'free' },
});

const STATUS_LABELS: Record<StationVariant, string> = {
  free: 'Free',
  open: 'Open',
  active: 'Playing',
  paused: 'Paused',
  locked: 'Time up',
  reserved: 'Reserved',
  booked: 'Booked',
  maintenance: 'Service',
};

export const CONSOLE_LABELS: Record<string, string> = {
  PS5: 'PlayStation 5',
  PS4: 'PlayStation 4',
};

export function consoleLabel(consoleType: string | undefined): string {
  return (consoleType && CONSOLE_LABELS[consoleType]) || (consoleType ?? '');
}

export type StationCardProps = {
  station: Station;
  selected?: boolean;
  onSelect?: (station: Station) => void;
  /**
   * When `['stations']` last landed, in local milliseconds — the clock's
   * `asOf` (see `stationClockSnapshot`). Defaults to now for a fresh render.
   */
  receivedAt?: number;
  className?: string;
};

export function StationCard({
  station,
  selected = false,
  onSelect,
  receivedAt,
  className,
}: StationCardProps) {
  const variant = stationVariant(station);
  const snapshot = stationClockSnapshot(station, receivedAt ?? Date.now());
  const session = station.session;
  const match = station.match;
  const arrival = station.arrival;

  const blocks = session?.blocks ?? 0;
  const purchasedSeconds = blocks * BLOCK_SECONDS;
  const remaining = snapshot?.remainingSeconds ?? 0;
  const fractionLeft =
    purchasedSeconds > 0 ? Math.max(0, Math.min(1, remaining / purchasedSeconds)) : 0;

  return (
    <button
      type="button"
      data-testid="station-card"
      data-station-id={station.id}
      data-variant={variant}
      data-selected={selected || undefined}
      aria-pressed={selected}
      onClick={() => onSelect?.(station)}
      className={cn(card({ variant, selected }), className)}
    >
      <span className="flex items-baseline gap-2.5">
        <span className="font-heading text-[27px] leading-none font-extrabold tracking-[-0.03em]">
          {station.name}
        </span>
        <span className="type-label opacity-60">{consoleLabel(station.consoleType)}</span>
        <span className={chip({ variant })}>
          {variant === 'reserved' && match ? 'Match' : STATUS_LABELS[variant]}
        </span>
      </span>

      <span className="text-body opacity-70">{detailLine(station)}</span>

      <span className="mt-1 flex items-baseline gap-2.5">
        <CountdownClock variant="card" snapshot={snapshot} label={timeLabel(station, remaining)} />
      </span>

      <span className="mt-auto block h-2 bg-track" aria-hidden="true">
        <span
          data-testid="station-progress"
          className={cn('block h-2', remaining > 0 ? 'bg-accent' : 'bg-text')}
          style={{ width: `${(fractionLeft * 100).toFixed(1)}%` }}
        />
      </span>

      <span className="mt-0.5 flex justify-between text-[12px] opacity-70">
        <span>{planLine(station)}</span>
        <span className="tabular">
          {arrival?.blocks ? `${formatBlocks(arrival.blocks)} prepaid` : paidLine(session)}
        </span>
      </span>
    </button>
  );
}

/** Who — or what — is on this console. Never invented: only what the row says. */
function detailLine(station: Station): string {
  const variant = stationVariant(station);
  const match = station.match;
  const arrival = station.arrival;

  switch (variant) {
    case 'reserved':
      if (match?.playerA && match?.playerB) return `${match.playerA} vs ${match.playerB}`;
      return match?.tournamentName ?? 'Reserved for a tournament';
    case 'booked':
      return arrival
        ? `${arrival.name ?? 'Booking'} · TOKEN ${formatToken(arrival.token ?? 0)}`
        : 'Checked-in arrival waiting';
    case 'maintenance':
      return 'Out of service';
    case 'free':
      return 'No session';
    default:
      return station.session?.memberId ? 'Member attached' : 'Walk-in';
  }
}

/** The kicker beside the digits — the prototype's `timeLabel`. */
function timeLabel(station: Station, remaining: number): string {
  const variant = stationVariant(station);
  const match = station.match;

  switch (variant) {
    case 'free':
      return 'no session';
    case 'booked':
      return 'waiting to be seated';
    case 'maintenance':
      return 'out of service';
    case 'reserved':
      if (!match) return 'blocked for event';
      return match.timeUp || remaining <= 0 ? 'match over' : 'match left';
    case 'open':
      return 'no time bought';
    case 'locked':
      return 'time up';
    case 'paused':
      return 'paused';
    default:
      return remaining > 0 ? 'left' : 'over';
  }
}

/** Bottom-left: what was bought, or what is holding the console. */
function planLine(station: Station): string {
  const variant = stationVariant(station);
  if (variant === 'reserved') return station.match?.tournamentName ?? 'No walk-in sessions';
  if (variant === 'free' || variant === 'maintenance') return '—';
  if (variant === 'booked') return 'Seat from the panel';
  const blocks = station.session?.blocks ?? 0;
  if (blocks === 0) return 'Add a 30 min block';
  return `${blocks} × 30-min block${blocks === 1 ? '' : 's'}`;
}

/** Bottom-right: how much of what was bought is already settled. */
function paidLine(session: Station['session']): string {
  if (!session) return '—';
  const blocks = session.blocks ?? 0;
  const paid = session.paidBlocks ?? 0;
  if (blocks === 0) return '—';
  const unpaid = Math.max(0, blocks - paid);
  return unpaid === 0 ? 'all paid' : `${unpaid} unpaid`;
}
