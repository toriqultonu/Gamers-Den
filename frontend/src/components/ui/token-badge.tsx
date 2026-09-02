/**
 * TokenBadge — docs/design.md §2: variants inline · stub, props `token`.
 *
 * Tokens are queue identity, not payment proof (frontend/ARCHITECTURE.md §5.12)
 * and the counter resets at venue midnight — so a token issued before today
 * carries its issue date, otherwise two "#04"s a day apart read alike.
 *
 * `stub` is the thermal-preview treatment (mono on paper). The printed stub
 * itself always comes from the server render (§5.6) — this only styles the
 * on-screen preview.
 */

import { cva } from 'class-variance-authority';
import { cn, type VariantProps } from './cn';

export const TOKEN_BADGE_VARIANTS = ['inline', 'stub'] as const;
export type TokenBadgeVariant = (typeof TOKEN_BADGE_VARIANTS)[number];

const badge = cva('inline-flex items-center gap-2 rounded-none tabular', {
  variants: {
    variant: {
      inline: 'bg-accent px-2 py-0.5 font-heading text-[16px] font-extrabold text-on-accent',
      stub: 'bg-paper px-2 py-0.5 type-mono font-bold text-[#201e1d]',
    },
  },
  defaultVariants: { variant: 'inline' },
});

/** `#04` — two digits, the way the queue and the stub both print it. */
export function formatToken(token: number): string {
  return `#${String(token).padStart(2, '0')}`;
}

export type TokenBadgeProps = VariantProps<typeof badge> & {
  token: number;
  /** Issue date (`YYYY-MM-DD`, venue timezone) — shown when it is not today. */
  issuedOn?: string;
  /** Today in the venue timezone; injected so the badge stays pure. */
  today?: string;
  className?: string;
};

export function TokenBadge({
  variant,
  token,
  issuedOn,
  today,
  className,
}: TokenBadgeProps) {
  const stale = Boolean(issuedOn && today && issuedOn !== today);
  return (
    <span
      data-variant={variant ?? 'inline'}
      data-stale={stale || undefined}
      className={cn(badge({ variant }), className)}
    >
      <span>{`TOKEN ${formatToken(token)}`}</span>
      {stale ? <span className="text-[10px] font-normal opacity-70">{issuedOn}</span> : null}
    </span>
  );
}
