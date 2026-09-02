'use client';

/**
 * Button — docs/design.md §2.
 *
 * Variants: primary · secondary · ghost · icon · block
 * States:   default · hover · active · focus-visible · disabled · loading
 *
 * Design rules kept here: radius 0, hover/active step one rung down the ramp,
 * 45% disabled opacity, 2px accent focus-visible outline (from the base layer,
 * restated so a primitive is legible on its own), and centered labels on the
 * full-width `block` variant.
 */

import { cva } from 'class-variance-authority';
import type { ButtonHTMLAttributes, ReactNode } from 'react';
import { forwardRef } from 'react';
import { cn, type VariantProps } from './cn';

export const BUTTON_VARIANTS = ['primary', 'secondary', 'ghost', 'icon', 'block'] as const;
export type ButtonVariant = (typeof BUTTON_VARIANTS)[number];

export const BUTTON_SIZES = ['sm', 'md', 'lg'] as const;
export type ButtonSize = (typeof BUTTON_SIZES)[number];

const button = cva(
  [
    'inline-flex items-center gap-2 rounded-none',
    'font-heading font-extrabold leading-tight',
    'border border-transparent',
    'cursor-pointer select-none',
    'transition-colors',
    'focus-visible:outline-2 focus-visible:outline-accent focus-visible:outline-offset-2',
    'disabled:cursor-not-allowed disabled:opacity-45',
    'aria-busy:cursor-progress',
  ],
  {
    variants: {
      variant: {
        primary: [
          'justify-center bg-accent text-on-accent',
          'hover:not-disabled:bg-accent-600 active:not-disabled:bg-accent-700',
        ],
        secondary: [
          'justify-center bg-transparent border-divider text-text',
          'hover:not-disabled:bg-neutral-200 active:not-disabled:bg-neutral-300',
        ],
        ghost: [
          'justify-center bg-transparent text-accent-strong px-1',
          'hover:not-disabled:bg-accent-tint active:not-disabled:bg-accent-200',
        ],
        icon: [
          'justify-center bg-transparent border-divider text-text p-0',
          'hover:not-disabled:bg-neutral-200 active:not-disabled:bg-neutral-300',
        ],
        // Full-width action — design.md §2: "labels centered on full-width actions".
        block: [
          'w-full justify-center bg-accent text-on-accent',
          'hover:not-disabled:bg-accent-600 active:not-disabled:bg-accent-700',
        ],
      },
      size: {
        sm: 'text-[13px] px-3 py-1',
        md: 'text-body px-4 py-2',
        lg: 'text-[15px] px-5 py-3',
      },
    },
    compoundVariants: [
      // The icon variant is a square affordance, so the size scale drives its
      // box rather than its padding.
      { variant: 'icon', size: 'sm', class: 'size-7 px-0 py-0' },
      { variant: 'icon', size: 'md', class: 'size-9 px-0 py-0' },
      { variant: 'icon', size: 'lg', class: 'size-11 px-0 py-0' },
      // Ghost keeps its inline padding tight at every size (DS `.btn-ghost`).
      { variant: 'ghost', size: 'sm', class: 'px-1' },
      { variant: 'ghost', size: 'md', class: 'px-1' },
      { variant: 'ghost', size: 'lg', class: 'px-2' },
    ],
    defaultVariants: { variant: 'secondary', size: 'md' },
  },
);

export type ButtonProps = Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'children'> &
  VariantProps<typeof button> & {
    /** Renders the spinner, marks the button busy and blocks activation. */
    loading?: boolean;
    children?: ReactNode;
  };

/** 12px ring, 2px stroke — the only motion in the system. */
function Spinner() {
  return (
    <span
      data-testid="button-spinner"
      aria-hidden="true"
      className="inline-block size-3 shrink-0 animate-spin border-2 border-current border-t-transparent"
    />
  );
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant, size, loading = false, disabled = false, className, children, type, ...props },
  ref,
) {
  const isDisabled = disabled || loading;
  return (
    <button
      ref={ref}
      type={type ?? 'button'}
      data-variant={variant ?? 'secondary'}
      data-loading={loading || undefined}
      disabled={isDisabled}
      aria-busy={loading || undefined}
      className={cn(button({ variant, size }), className)}
      {...props}
    >
      {loading ? <Spinner /> : null}
      {children}
    </button>
  );
});
