'use client';

/**
 * CountdownClock — docs/design.md §2: variants panel (50px) · card (52px) ·
 * match tile (26px); states running · paused · overtime · none.
 *
 * The one rule that matters here is frontend/ARCHITECTURE.md §5.2: **the local
 * clock is never consulted.** A reading is a server `remainingSeconds` plus the
 * instant it was true, and every tick re-derives the remainder from that plus
 * the offset `lib/api.ts` measures off each response's `Date` header. A
 * terminal whose Windows clock is ten minutes fast shows the same time left as
 * the till beside it, and a new snapshot — a resumed session, a match extended
 * by +5 min — re-bases the display instead of drifting from it.
 *
 * Overtime is a state, not an error: a session that runs past its blocks shows
 * `−2:15` in accent, because that is what the floor has to see.
 */

import { cva } from 'class-variance-authority';
import { cn, type VariantProps } from '@/components/ui/cn';
import { formatCountdown, type ClockSnapshot } from '@/lib/time';
import { useCountdown } from '@/lib/use-countdown';

export const COUNTDOWN_VARIANTS = ['panel', 'card', 'match'] as const;
export type CountdownVariant = (typeof COUNTDOWN_VARIANTS)[number];

export const CLOCK_STATES = ['running', 'paused', 'overtime', 'none'] as const;
export type ClockState = (typeof CLOCK_STATES)[number];

const clock = cva('font-heading font-extrabold tabular leading-none', {
  variants: {
    variant: {
      panel: 'text-[50px] tracking-[-0.045em]',
      card: 'text-[52px] tracking-[-0.045em]',
      match: 'text-[26px] tracking-[-0.03em]',
    },
    state: {
      running: 'text-text',
      paused: 'text-text opacity-60',
      overtime: 'text-accent-strong',
      none: 'text-text opacity-35',
    },
  },
  defaultVariants: { variant: 'card', state: 'none' },
});

/** What a console with no clock at all reads — design.md's `none` state. */
export const NO_CLOCK = '--:--';

export type CountdownClockProps = VariantProps<typeof clock> & {
  /**
   * The server's reading. Given one, the clock ticks itself; `null` is the
   * `none` state (a free console).
   */
  snapshot?: ClockSnapshot | null;
  /**
   * An already-derived remainder, for a parent that ticks several clocks from
   * one timer. Ignored when `snapshot` is given.
   */
  remainingSec?: number | null;
  /** The kicker under/beside the digits — "left", "match over", "paused". */
  label?: string;
  className?: string;
};

/**
 * `running` while it drains, `overtime` once it passes zero whatever the
 * session state, `paused` for a held reading, `none` for no clock.
 */
export function clockStateOf(
  remaining: number | null | undefined,
  running: boolean | undefined,
): ClockState {
  if (remaining === null || remaining === undefined) return 'none';
  if (remaining < 0) return 'overtime';
  return running ? 'running' : 'paused';
}

export function CountdownClock({
  variant,
  state,
  snapshot,
  remainingSec,
  label,
  className,
}: CountdownClockProps) {
  // Always mounted — a hook cannot be conditional, and `null` is a reading the
  // tick understands (it holds at `none`).
  const ticked = useCountdown(snapshot ?? null);
  const seconds = snapshot !== undefined && snapshot !== null ? ticked : (remainingSec ?? null);
  const resolved = state ?? clockStateOf(seconds, snapshot?.running);

  return (
    <span className={cn('inline-flex items-baseline gap-2.5', className)}>
      <span
        data-testid="countdown-clock"
        data-variant={variant ?? 'card'}
        data-state={resolved}
        className={clock({ variant, state: resolved })}
      >
        {resolved === 'none' || seconds === null ? NO_CLOCK : formatCountdown(seconds)}
      </span>
      {label ? <span className="type-label opacity-55">{label}</span> : null}
    </span>
  );
}
