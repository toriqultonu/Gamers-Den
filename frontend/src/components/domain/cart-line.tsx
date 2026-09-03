'use client';

/**
 * CartLine — the bill's editable row (docs/design.md §2, BillPanel row).
 *
 * `−` at quantity 1 removes the line rather than sitting there disabled: on a
 * till the way you undo a mis-tap is to press minus until it is gone, and a
 * floor at 1 makes that a two-step operation with a dead button in the middle.
 *
 * The same row serves food, tournament entries and play tickets. The first
 * writes through to the server cart; the other two live in the bill draft
 * until settle sends them as `tournamentEntries[]` / `playTickets[]`. That
 * difference belongs to the caller — a row is a row.
 */

import { Button } from '@/components/ui/button';
import { cn } from '@/components/ui/cn';
import { formatBDT } from '@/lib/money';

export const CART_LINE_KINDS = ['fnb', 'entry', 'ticket'] as const;
export type CartLineKind = (typeof CART_LINE_KINDS)[number];

export type CartLineProps = {
  kind?: CartLineKind;
  name: string;
  qty: number;
  lineTotal: number;
  /** Ceiling on `+` — a tournament's remaining slots, say. */
  max?: number;
  disabled?: boolean;
  onChange: (qty: number) => void;
  className?: string;
};

export function CartLine({
  kind = 'fnb',
  name,
  qty,
  lineTotal,
  max,
  disabled = false,
  onChange,
  className,
}: CartLineProps) {
  const canAdd = !disabled && (max === undefined || qty < max);

  return (
    <div
      data-testid="cart-line"
      data-kind={kind}
      className={cn('flex items-center gap-2 text-body', className)}
    >
      <span className="min-w-0 flex-1 truncate">{name}</span>
      <Button
        variant="secondary"
        size="sm"
        className="size-6.5 justify-center p-0"
        disabled={disabled}
        aria-label={qty <= 1 ? `Remove ${name}` : `One less ${name}`}
        onClick={() => onChange(qty - 1)}
      >
        −
      </Button>
      <span data-testid="cart-line-qty" className="w-4.5 text-center tabular">
        {qty}
      </span>
      <Button
        variant="secondary"
        size="sm"
        className="size-6.5 justify-center p-0"
        disabled={!canAdd}
        aria-label={`One more ${name}`}
        onClick={() => onChange(qty + 1)}
      >
        +
      </Button>
      <span className="w-15 text-right tabular">{formatBDT(lineTotal)}</span>
    </div>
  );
}
