/**
 * StatTile — docs/design.md §2 (primitives row); the S2 header grid.
 *
 * Kicker + big tabular figure + quiet sub-line. The `accent` variant is the
 * one filled tile the overview grid ends on (net profit).
 */

import { cva } from 'class-variance-authority';
import type { ReactNode } from 'react';
import { cn, type VariantProps } from './cn';

export const STAT_TILE_VARIANTS = ['default', 'accent'] as const;
export type StatTileVariant = (typeof STAT_TILE_VARIANTS)[number];

const tile = cva('flex flex-col gap-0.5 p-4', {
  variants: {
    variant: {
      default: 'bg-transparent text-text',
      accent: 'bg-accent text-on-accent',
    },
  },
  defaultVariants: { variant: 'default' },
});

export type StatTileProps = VariantProps<typeof tile> & {
  label: string;
  value: ReactNode;
  /** Quiet line under the figure — comparison, count, caveat. */
  hint?: ReactNode;
  className?: string;
};

export function StatTile({ variant, label, value, hint, className }: StatTileProps) {
  const accent = variant === 'accent';
  return (
    <div data-variant={variant ?? 'default'} className={cn(tile({ variant }), className)}>
      <span className={cn('type-label', accent ? 'opacity-85' : 'opacity-55')}>{label}</span>
      <span className="font-heading text-h1 font-extrabold tabular">{value}</span>
      {hint ? (
        <span className={cn('text-[12px]', accent ? 'opacity-85' : 'opacity-65')}>{hint}</span>
      ) : null}
    </div>
  );
}
