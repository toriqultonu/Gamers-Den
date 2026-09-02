/**
 * Tag — docs/design.md §2. Variants: accent · neutral · outline. Static.
 *
 * Body copy is never raw accent (design.md §3 contrast rules): the accent tag
 * pairs `accent-tint` (ramp 100) with ramp 800 ink, and the outline tag draws
 * its rule in accent while the label stays `accent-strong`.
 */

import { cva } from 'class-variance-authority';
import type { HTMLAttributes } from 'react';
import { cn, type VariantProps } from './cn';

export const TAG_VARIANTS = ['accent', 'neutral', 'outline'] as const;
export type TagVariant = (typeof TAG_VARIANTS)[number];

const tag = cva(
  'inline-flex items-center rounded-none px-2 py-0.5 text-[11px] leading-tight border border-transparent',
  {
    variants: {
      variant: {
        accent: 'bg-accent-tint text-accent-800',
        neutral: 'bg-neutral-100 text-neutral-800',
        outline: 'bg-transparent border-accent text-accent-strong',
      },
    },
    defaultVariants: { variant: 'neutral' },
  },
);

export type TagProps = HTMLAttributes<HTMLSpanElement> & VariantProps<typeof tag>;

export function Tag({ variant, className, ...props }: TagProps) {
  return (
    <span data-variant={variant ?? 'neutral'} className={cn(tag({ variant }), className)} {...props} />
  );
}
