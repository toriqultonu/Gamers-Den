'use client';

/**
 * AvatarSwatch — docs/design.md §2 (primitives row); S13 profile colour.
 *
 * Two shapes of the same thing: the colour chip in the settings palette, and
 * the initials avatar it drives in the sidebar and on shift records. Selection
 * is a 3px ink outline (the prototype's swatch treatment); no radius, ever —
 * the avatar is a square, not a circle.
 */

import { cva } from 'class-variance-authority';
import { cn, type VariantProps } from './cn';

/** The S13 palette — prototype `cfg.swatches`. */
export const AVATAR_COLORS = [
  '#201e1d',
  '#ec3013',
  '#0f62fe',
  '#198038',
  '#8a3ffc',
  '#ff832b',
] as const;
export type AvatarColor = (typeof AVATAR_COLORS)[number];

export const AVATAR_SWATCH_SIZES = ['sm', 'md', 'lg'] as const;
export type AvatarSwatchSize = (typeof AVATAR_SWATCH_SIZES)[number];

const swatch = cva('grid shrink-0 place-items-center rounded-none font-heading font-extrabold', {
  variants: {
    size: {
      sm: 'size-7 text-[11px]',
      md: 'size-9 text-[13px]',
      lg: 'size-16 text-[26px]',
    },
  },
  defaultVariants: { size: 'md' },
});

export type AvatarSwatchProps = VariantProps<typeof swatch> & {
  /** Chosen colour; falls back to the ink/ground pair when unset. */
  color?: string | null;
  /** Initials to draw inside — omit for a bare palette chip. */
  initials?: string;
  selected?: boolean;
  /** Makes the swatch a button (the settings palette); omit for a static avatar. */
  onSelect?: () => void;
  /** Accessible name for the palette chip. */
  label?: string;
  className?: string;
};

export function AvatarSwatch({
  size,
  color,
  initials,
  selected = false,
  onSelect,
  label,
  className,
}: AvatarSwatchProps) {
  const style = color
    ? { backgroundColor: color, color: '#ffffff' }
    : { backgroundColor: 'var(--gd-text)', color: 'var(--gd-bg)' };

  const classes = cn(
    swatch({ size }),
    selected && 'outline-[3px] outline-offset-2 outline-text',
    onSelect &&
      'cursor-pointer focus-visible:outline-2 focus-visible:outline-accent focus-visible:outline-offset-2',
    className,
  );

  if (!onSelect) {
    return (
      <span data-selected={selected || undefined} style={style} className={classes}>
        {initials}
      </span>
    );
  }

  return (
    <button
      type="button"
      aria-pressed={selected}
      aria-label={label ?? (color ? `Avatar colour ${color}` : 'Default avatar colour')}
      data-selected={selected || undefined}
      onClick={onSelect}
      style={style}
      className={classes}
    >
      {initials}
    </button>
  );
}
