/**
 * ProgressBar — docs/design.md §2 (primitives row).
 *
 * `color.track` behind, accent in front; the `alt` variant paints the second
 * series colour (`color.bar-alt`) for the stock/second-series rows.
 */

import type { ReactNode } from 'react';
import { cn } from './cn';

export const PROGRESS_VARIANTS = ['accent', 'alt'] as const;
export type ProgressVariant = (typeof PROGRESS_VARIANTS)[number];

export type ProgressBarProps = {
  value: number;
  max?: number;
  variant?: ProgressVariant;
  /** Row label rendered left of the track. */
  label?: ReactNode;
  /** Right-aligned readout — the number, formatted by the caller. */
  valueLabel?: ReactNode;
  className?: string;
};

export function ProgressBar({
  value,
  max = 100,
  variant = 'accent',
  label,
  valueLabel,
  className,
}: ProgressBarProps) {
  const safeMax = max > 0 ? max : 1;
  const clamped = Math.min(Math.max(value, 0), safeMax);
  const pct = (clamped / safeMax) * 100;

  return (
    <div className={cn('flex items-center gap-2.5', className)}>
      {label ? <span className="shrink-0 text-[12px] opacity-60">{label}</span> : null}
      <div
        role="progressbar"
        aria-valuenow={clamped}
        aria-valuemin={0}
        aria-valuemax={safeMax}
        data-variant={variant}
        className="h-3.5 flex-1 bg-track"
      >
        <div
          data-testid="progress-fill"
          style={{ width: `${pct}%` }}
          className={cn('h-full', variant === 'alt' ? 'bg-bar-alt' : 'bg-accent')}
        />
      </div>
      {valueLabel ? (
        <span className="shrink-0 text-right text-[12px] tabular">{valueLabel}</span>
      ) : null}
    </div>
  );
}
