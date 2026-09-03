'use client';

/**
 * MenuItemCard — docs/design.md §2: variants "item, tournament entry, play
 * ticket"; states "hover, low-stock, out-of-stock/full (disabled)".
 *
 * One card, three things it can be selling, because they sit in one grid and
 * the operator taps them the same way. What differs is the rule that turns the
 * card off:
 *
 *  - an **item** is off when the shelf is empty (`outOfStock`);
 *  - a **tournament entry** is off when the event is full (`slotsLeft` 0) —
 *    the client-side twin of 409 `TOURNAMENT_FULL` (docs/tournaments.md §5);
 *  - a **play ticket** is never off for want of a console. It is prepaid time
 *    in a queue and is sold precisely when everything is busy
 *    (docs/bookings.md §3).
 *
 * The accent border marks the two POS-only categories, as in the prototype:
 * an entry card is outlined in accent, a ticket card in `text`.
 */

import { cn } from '@/components/ui/cn';
import { formatBDT } from '@/lib/money';

export const MENU_CARD_VARIANTS = ['item', 'entry', 'ticket'] as const;
export type MenuCardVariant = (typeof MENU_CARD_VARIANTS)[number];

export type MenuItemCardProps = {
  variant?: MenuCardVariant;
  /** The category kicker over the title ("Beverage", "Tournament", …). */
  kicker: string;
  name: string;
  price: number;
  /** The line under the price: stock left, slots left, "prepaid · gets a token". */
  note: string;
  /** Renders the note in accent — low stock, or the last couple of slots. */
  noteUrgent?: boolean;
  disabled?: boolean;
  onAdd?: () => void;
};

export function MenuItemCard({
  variant = 'item',
  kicker,
  name,
  price,
  note,
  noteUrgent = false,
  disabled = false,
  onAdd,
}: MenuItemCardProps) {
  return (
    <button
      type="button"
      data-testid="menu-card"
      data-variant={variant}
      disabled={disabled}
      aria-disabled={disabled || undefined}
      onClick={() => onAdd?.()}
      className={cn(
        'flex min-h-[104px] flex-col gap-1 border-2 bg-surface p-3.5 text-left',
        'hover:not-disabled:bg-card focus-visible:outline-2 focus-visible:outline-accent',
        'disabled:cursor-not-allowed disabled:opacity-45',
        variant === 'entry' && 'border-accent',
        variant === 'ticket' && 'border-text',
        variant === 'item' && 'border-divider',
      )}
    >
      <span className="type-label text-accent-strong">{kicker}</span>
      <span className="font-heading text-[15px] leading-tight font-extrabold">{name}</span>
      <span className="mt-auto flex items-baseline justify-between gap-2">
        <span className="font-heading text-[18px] font-extrabold tabular">{formatBDT(price)}</span>
        <span
          data-testid="menu-card-note"
          className={cn('text-[11px]', noteUrgent ? 'text-accent-strong' : 'opacity-55')}
        >
          {note}
        </span>
      </span>
    </button>
  );
}
