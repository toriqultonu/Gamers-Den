'use client';

/**
 * TimeStepper — docs/design.md §2: "−30 disabled at 30 min", props
 * `blocks, onChange`.
 *
 * Play time is bought in 30-minute blocks, so the stepper counts blocks and
 * renders the human length beside them ("1 h 30 min" / "2 × 30 min"), exactly
 * as the booking form does in the prototype. One block is the floor — the
 * −30 min control is disabled there, never hidden.
 */

import { Button } from './button';
import { cn } from './cn';

/** A block is 30 minutes everywhere in the system (docs/bookings.md). */
export const BLOCK_MINUTES = 30;

export type TimeStepperProps = {
  blocks: number;
  onChange: (blocks: number) => void;
  /** Floor, in blocks. Design.md pins it at 1 (= 30 min). */
  min?: number;
  /** Optional ceiling, in blocks. */
  max?: number;
  disabled?: boolean;
  className?: string;
};

/** "30 min" / "1 h" / "1 h 30 min" — the prototype's `lenStr`. */
export function formatBlocks(blocks: number): string {
  const minutes = blocks * BLOCK_MINUTES;
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  if (hours === 0) return `${rest} min`;
  if (rest === 0) return `${hours} h`;
  return `${hours} h ${rest} min`;
}

export function TimeStepper({
  blocks,
  onChange,
  min = 1,
  max,
  disabled = false,
  className,
}: TimeStepperProps) {
  const canDecrease = !disabled && blocks > min;
  const canIncrease = !disabled && (max === undefined || blocks < max);

  return (
    <div className={cn('flex items-center gap-2.5', className)}>
      <Button
        variant="secondary"
        onClick={() => onChange(blocks - 1)}
        disabled={!canDecrease}
        aria-label="Remove 30 minutes"
        className="w-24"
      >
        −30 min
      </Button>
      <div className="flex-1 text-center">
        <div data-testid="time-stepper-length" className="font-heading text-[22px] font-extrabold tabular">
          {formatBlocks(blocks)}
        </div>
        <div className="text-[10px] opacity-55">{`${blocks} × ${BLOCK_MINUTES} min`}</div>
      </div>
      <Button
        variant="secondary"
        onClick={() => onChange(blocks + 1)}
        disabled={!canIncrease}
        aria-label="Add 30 minutes"
        className="w-24"
      >
        +30 min
      </Button>
    </div>
  );
}
