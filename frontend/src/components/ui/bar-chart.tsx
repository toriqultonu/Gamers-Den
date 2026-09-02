/**
 * BarChart — docs/design.md §2 (primitives row); plain SVG, no chart library
 * (frontend/ARCHITECTURE.md §2 "Charts: plain SVG/divs").
 *
 * One accent series with an optional per-bar `alt` flag painting
 * `color.bar-alt` (design.md §3: "second chart series"). Empty renders the
 * S9 copy — "Not enough data yet" — rather than an empty axis.
 */

import type { ReactNode } from 'react';
import { cn } from './cn';

export type BarDatum = {
  label: string;
  value: number;
  /** Paint this bar in the second-series colour. */
  alt?: boolean;
};

export type BarChartProps = {
  data: readonly BarDatum[];
  /** Plot height in px, excluding the baseline rule. */
  height?: number;
  /** Names the chart for assistive tech. */
  label: string;
  /** Shown instead of the plot when there is nothing to draw. */
  empty?: ReactNode;
  className?: string;
};

const GAP = 4;

export function BarChart({ data, height = 150, label, empty, className }: BarChartProps) {
  const max = data.reduce((peak, datum) => Math.max(peak, datum.value), 0);

  if (data.length === 0 || max <= 0) {
    return (
      <div
        data-testid="bar-chart-empty"
        className={cn('border-2 border-divider p-4 text-[13px] opacity-60', className)}
      >
        {empty ?? 'Not enough data yet'}
      </div>
    );
  }

  // A unit-width viewBox scaled with preserveAspectRatio="none" keeps the bars
  // flush at any container width without measuring the DOM.
  const width = data.length * (100 + GAP) - GAP;

  return (
    <svg
      role="img"
      aria-label={label}
      viewBox={`0 0 ${width} ${height}`}
      preserveAspectRatio="none"
      style={{ height }}
      className={cn('block w-full border-b-2 border-text', className)}
    >
      {data.map((datum, index) => {
        const barHeight = Math.max((Math.max(datum.value, 0) / max) * height, 1);
        return (
          <rect
            key={`${datum.label}-${index}`}
            data-testid="bar-chart-bar"
            data-series={datum.alt ? 'alt' : 'accent'}
            x={index * (100 + GAP)}
            y={height - barHeight}
            width={100}
            height={barHeight}
            className={datum.alt ? 'fill-bar-alt' : 'fill-accent'}
          >
            <title>{`${datum.label}: ${datum.value}`}</title>
          </rect>
        );
      })}
    </svg>
  );
}
