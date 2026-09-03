'use client';

/**
 * Cart writes — the one optimistic path on this screen
 * (frontend/ARCHITECTURE.md §5.3: "Optimistic: cart lines, block ±").
 *
 * Tapping a menu card has to feel like pressing a key on a till, so the line
 * appears at once. What makes that safe rather than merely fast is that the
 * cart held in the store is never a guess for longer than a round trip:
 *
 *   1. `onMutate` applies the predicted cart and keeps the old one;
 *   2. the server answers with the **whole** `Cart` and that replaces the
 *      prediction outright — prices, line totals and cart total are the
 *      server's snapshot, not the client's arithmetic (this is the
 *      reconciliation, and it is why a stale menu price cannot stick);
 *   3. `OUT_OF_STOCK` (or anything else) puts the previous cart back and hands
 *      the screen a notice. An error never destroys entered data (§4.4).
 *
 * Carts are server-side from the first line-add (§5.7), so the first add opens
 * one: `POST /carts` — which for a session returns the cart it already has
 * rather than failing, so a mid-bill refresh finds its way back to the same
 * rows.
 *
 * `PUT /carts/{id}/lines` is not on the idempotency-guarded list
 * (api-contract.md §1) — it sets an absolute quantity rather than applying a
 * delta, so a replayed request is already harmless.
 */

import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api, type Schemas } from '@/lib/api';
import { queryKeys } from '@/lib/query-keys';
import { useAppStore, type Cart, type Item } from './bill-store';

export type CreateCartRequest = Schemas['CreateCartRequest'];

export type SetLineInput = {
  /** The menu row being sold — its price seeds the optimistic line. */
  item: Item;
  /** The absolute quantity to end up at. 0 removes the line. */
  qty: number;
  /** The session this cart hangs off, for a station bill. */
  sessionId?: number | null;
};

/**
 * Set one cart line to an absolute quantity, optimistically.
 *
 * The screen works in deltas ("+", "−"); it resolves them against the cart it
 * is showing and calls this with the answer, because `PUT …/lines` takes a
 * quantity and two taps racing on a delta would otherwise disagree.
 */
export function useSetCartLine() {
  const client = useQueryClient();

  return useMutation<Cart, unknown, SetLineInput, { previous: Cart | null }>({
    async mutationFn({ item, qty, sessionId }) {
      const cartId = await ensureCart(sessionId);
      return api.put<Cart>(`/carts/${cartId}/lines`, { itemId: item.id, qty });
    },

    onMutate({ item, qty }) {
      const previous = useAppStore.getState().draft.cart;
      useAppStore.getState().setCart(applyLine(previous, item, qty));
      return { previous };
    },

    // The server's cart replaces the prediction wholesale — including the
    // prices it snapshotted, which the client had no business guessing.
    onSuccess(cart) {
      useAppStore.getState().setCart(cart);
    },

    onError(_error, _input, context) {
      useAppStore.getState().setCart(context?.previous ?? null);
    },

    // The bill's F&B figure and the shelf both moved.
    onSettled(_cart, _error, { sessionId }) {
      void client.invalidateQueries({ queryKey: queryKeys.items.all() });
      if (typeof sessionId === 'number') {
        void client.invalidateQueries({ queryKey: queryKeys.sessions.bill(sessionId) });
      }
    },
  });
}

/**
 * The cart this bill writes into, opening one on first use.
 *
 * `POST /carts` with a `sessionId` is safe to repeat — `carts.session_id` is
 * UNIQUE and the endpoint answers 200 with the existing cart. A counter cart
 * has no such key, so it is opened once and kept in the draft until the bill
 * is cleared.
 */
async function ensureCart(sessionId: number | null | undefined): Promise<number> {
  const existing = useAppStore.getState().draft.cart;
  if (typeof existing?.id === 'number') return existing.id;

  const body: CreateCartRequest =
    typeof sessionId === 'number'
      ? { type: 'SESSION', sessionId }
      : { type: 'COUNTER' };

  const cart = await api.post<Cart>('/carts', body);
  useAppStore.getState().setCart(mergeOpenedCart(useAppStore.getState().draft.cart, cart));
  if (typeof cart.id !== 'number') {
    throw new Error('The server opened a cart without an id.');
  }
  return cart.id;
}

/**
 * The freshly opened cart, keeping whatever the optimistic patch has already
 * drawn. The open happens *after* `onMutate` has painted the line, and a bare
 * empty cart from the server would blank it for one frame.
 */
function mergeOpenedCart(pending: Cart | null, opened: Cart): Cart {
  if (!pending || (pending.lines ?? []).length === 0) return opened;
  return { ...opened, lines: pending.lines, total: pending.total };
}

/**
 * The predicted cart after setting one line to `qty` — pure, so the optimistic
 * step is testable without a server.
 *
 * It prices at the menu's price, which is the same price the server snapshots
 * for a new line. Where the two can differ (a price edited in S10 between the
 * menu read and the tap) the server's answer wins a beat later.
 */
export function applyLine(cart: Cart | null, item: Item, qty: number): Cart {
  const base: Cart = cart ?? { type: 'COUNTER', lines: [], total: 0 };
  const lines = base.lines ?? [];
  const target = Math.max(0, Math.trunc(qty));

  const without = lines.filter((line) => line.itemId !== item.id);
  const existing = lines.find((line) => line.itemId === item.id);
  const unitPrice = existing?.unitPrice ?? item.price ?? 0;

  const next =
    target === 0
      ? without
      : [
          ...without,
          {
            itemId: item.id,
            name: existing?.name ?? item.name,
            category: existing?.category ?? item.category,
            unitPrice,
            qty: target,
            lineTotal: unitPrice * target,
          },
        ];

  // Keep the operator's reading order stable: a re-added line goes back where
  // it was rather than jumping to the bottom of the bill.
  const ordered = lines
    .map((line) => next.find((candidate) => candidate.itemId === line.itemId))
    .filter((line): line is NonNullable<typeof line> => line !== undefined);
  const added = next.filter(
    (line) => !lines.some((existingLine) => existingLine.itemId === line.itemId),
  );
  const merged = [...ordered, ...added];

  return {
    ...base,
    lines: merged,
    total: merged.reduce((sum, line) => sum + (line.lineTotal ?? 0), 0),
  };
}
