/**
 * Payment shapes — docs/api-contract.md (Billing & payments).
 *
 * These schemas guard the *form*, not the server. The server prices and settles
 * and is always authoritative; validating here means the operator sees "enter
 * the TrxID" under the field instead of a 409 banner after the tender is typed.
 * The two rules mirrored client-side are the ones with a domain code behind
 * them: `SPLIT_MISMATCH` and `PAYMENT_REF_REQUIRED`.
 *
 * The second half of this file is the **split draft** — the rows the panel
 * actually edits. They are kept as the operator's own text rather than parsed
 * numbers, because a settle that comes back 409 has to hand the entered figures
 * back exactly as typed (§4.4: an error never destroys entered data). Every
 * rule that decides what the rows become is a pure function here, so the panel
 * only renders and the store only holds.
 */

import { z } from 'zod';
import type { Schemas } from '@/lib/api';
import { parseAmount } from '@/lib/money';

/** The four tenders the drawer knows (`PaymentSplit.method`). */
export const PAYMENT_METHODS = ['CASH', 'BKASH', 'NAGAD', 'WALLET'] as const;
export type PaymentMethod = (typeof PAYMENT_METHODS)[number];

/** bKash and Nagad are recorded manually — a TrxID is the only proof we hold. */
export const MFS_METHODS: readonly PaymentMethod[] = ['BKASH', 'NAGAD'];

export const PAYMENT_METHOD_LABELS: Record<PaymentMethod, string> = {
  CASH: 'Cash',
  BKASH: 'bKash',
  NAGAD: 'Nagad',
  WALLET: 'Wallet',
};

export function isMfs(method: PaymentMethod): boolean {
  return MFS_METHODS.includes(method);
}

export const paymentMethodSchema = z.enum(PAYMENT_METHODS);

export const paymentSplitSchema = z
  .object({
    method: paymentMethodSchema,
    amount: z.int().nonnegative(),
    paymentRef: z.string().trim().max(64).optional(),
  })
  .refine((split) => !MFS_METHODS.includes(split.method) || Boolean(split.paymentRef), {
    // Mirrors 409 PAYMENT_REF_REQUIRED.
    error: 'Enter the bKash/Nagad TrxID.',
    path: ['paymentRef'],
  });

export type PaymentSplitInput = z.infer<typeof paymentSplitSchema>;

/**
 * A settle names at most one of a session and a counter cart.
 *
 * "Exactly one of the two is given; sending both, or neither, is 400" is the
 * headline rule — but `PaymentService.resolve` softens the second half: a
 * walk-up buying only tournament entries or play tickets has neither a seat nor
 * a basket, and settles against an empty target. So *both* is always wrong,
 * while *neither* is only wrong with nothing else on the bill — which is why
 * that half of the check lives on the request below, where the ticket and entry
 * lines are visible.
 */
export const settleTargetSchema = z
  .object({
    sessionId: z.int().positive().optional(),
    cartId: z.int().positive().optional(),
  })
  .refine((target) => !(target.sessionId && target.cartId), {
    error: 'Settle a session or a counter cart — not both.',
  });

export const playTicketLineSchema = z.object({
  consoleType: z.enum(['PS5', 'PS4']),
  blocks: z.int().min(1).max(48),
  playerName: z.string().trim().max(80).optional(),
});

export const tournamentEntryLineSchema = z.object({
  tournamentId: z.int().positive(),
  playerName: z.string().trim().max(80).optional(),
  memberId: z.int().positive().optional(),
});

export const settleRequestSchema = z
  .object({
    target: settleTargetSchema,
    // A bill covered entirely by points takes no tenders at all — `payment_splits`
    // carries CHECK (amount <> 0), so "paid nothing" is an empty list, never a
    // zero row (billing/domain/Settlement.java).
    splits: z.array(paymentSplitSchema),
    redeemPoints: z.int().nonnegative().optional(),
    playTickets: z.array(playTicketLineSchema).optional(),
    tournamentEntries: z.array(tournamentEntryLineSchema).optional(),
  })
  .refine(
    (request) =>
      Boolean(request.target.sessionId) ||
      Boolean(request.target.cartId) ||
      (request.playTickets ?? []).length > 0 ||
      (request.tournamentEntries ?? []).length > 0,
    { error: 'There is nothing to settle.', path: ['target'] },
  );

export type SettleRequestInput = z.infer<typeof settleRequestSchema>;

/** The generated request shape this form must still fit. */
export type SettleRequest = Schemas['SettleRequest'];

/** What `POST /payments` answers with — the receipt and any tokens it issued. */
export type SettleResult = Schemas['SettleResult'];

/** What the splits add up to — the number `SPLIT_MISMATCH` compares against. */
export function splitTotal(splits: readonly { amount?: number }[]): number {
  return splits.reduce((sum, split) => sum + (split.amount ?? 0), 0);
}

/**
 * The client-side half of `SPLIT_MISMATCH`: the tender must equal what is due.
 * The server re-checks and wins; this only keeps the Settle button honest.
 *
 * `redeemPoints` is here for callers holding a gross figure rather than a net
 * one — `billTotals().due` is already post-discount, so it passes nothing.
 */
export function splitsBalance(
  splits: readonly { amount?: number }[],
  amountDue: number,
  redeemPoints = 0,
): boolean {
  return splitTotal(splits) + redeemPoints === amountDue;
}

/* --------------------------------------------------------- the split draft */

/**
 * One editable tender row. `amount` is the raw text of the field, so `"1,20"`
 * survives a failed settle and a re-render as `"1,20"` rather than becoming 120
 * or 0 behind the operator's back.
 */
export type PaymentSplitDraft = {
  method: PaymentMethod;
  amount: string;
  paymentRef: string;
};

export function splitDraft(method: PaymentMethod, amount: number, paymentRef = ''): PaymentSplitDraft {
  return { method, amount: String(Math.max(0, Math.trunc(amount))), paymentRef };
}

/** The taka a row is actually tendering, or `null` when the text is not a number. */
export function draftAmount(split: PaymentSplitDraft): number | null {
  const trimmed = split.amount.trim();
  if (trimmed === '') return 0;
  return parseAmount(trimmed);
}

/**
 * The rows the panel shows.
 *
 * An untouched draft is one cash row for the whole bill: that is the sale the
 * counter takes all day, and deriving it rather than seeding it means the row
 * keeps following `due` as items go on the bill instead of going stale the
 * moment a drink is added. The first edit materialises the array and from then
 * on the figures are the operator's.
 */
export function effectiveSplits(
  splits: readonly PaymentSplitDraft[],
  due: number,
): PaymentSplitDraft[] {
  if (splits.length > 0) return [...splits];
  return [splitDraft('CASH', due)];
}

/** The methods currently on the split. */
export function selectedMethods(splits: readonly PaymentSplitDraft[]): PaymentMethod[] {
  return splits.map((split) => split.method);
}

/**
 * Turn a method on or off.
 *
 * Adding gives the new row whatever is not yet covered, so "cash ৳500, the rest
 * on bKash" is two taps. Removing hands its amount back to the first remaining
 * row rather than leaving the bill short — the panel should not need a third
 * tap to undo a mis-tap. Removing the last row empties the draft, which puts
 * the derived full-bill cash row back.
 */
export function toggleSplitMethod(
  splits: readonly PaymentSplitDraft[],
  method: PaymentMethod,
  due: number,
): PaymentSplitDraft[] {
  const rows = effectiveSplits(splits, due);
  const present = rows.some((row) => row.method === method);

  if (!present) {
    const covered = tenderedTotal(rows);
    return [...rows, splitDraft(method, Math.max(0, due - covered))];
  }

  const remaining = rows.filter((row) => row.method !== method);
  if (remaining.length === 0) return [];
  const others = tenderedTotal(remaining.slice(1));
  return [splitDraft(remaining[0].method, Math.max(0, due - others), remaining[0].paymentRef),
    ...remaining.slice(1)];
}

/** Set one row's amount to what was typed, verbatim. */
export function setSplitAmount(
  splits: readonly PaymentSplitDraft[],
  method: PaymentMethod,
  amount: string,
  due: number,
): PaymentSplitDraft[] {
  return effectiveSplits(splits, due).map((row) =>
    row.method === method ? { ...row, amount } : row,
  );
}

/** Set one row's TrxID. */
export function setSplitRef(
  splits: readonly PaymentSplitDraft[],
  method: PaymentMethod,
  paymentRef: string,
  due: number,
): PaymentSplitDraft[] {
  return effectiveSplits(splits, due).map((row) =>
    row.method === method ? { ...row, paymentRef } : row,
  );
}

/** What the rows tender in total; unparseable text counts as nothing. */
export function tenderedTotal(splits: readonly PaymentSplitDraft[]): number {
  return splits.reduce((sum, split) => sum + (draftAmount(split) ?? 0), 0);
}

export type SplitIssues = {
  /** What the rows come to. */
  tendered: number;
  /** `due − tendered`: positive is short, negative is over. */
  remainder: number;
  /** The `SPLIT_MISMATCH` rule, before the request is ever sent. */
  balanced: boolean;
  /** Rows whose amount is not a whole number of taka. */
  unreadable: PaymentMethod[];
  /** MFS rows with no TrxID — the `PAYMENT_REF_REQUIRED` rule. */
  missingRef: PaymentMethod[];
  /** A wallet row past the member's balance (`WALLET_INSUFFICIENT`). */
  walletOver: boolean;
  /** True when the settle is worth sending. */
  ok: boolean;
};

/**
 * Everything that stops the Settle button, in one pass.
 *
 * All three refusals are the server's to make — this is the same arithmetic run
 * a beat earlier so the operator fixes the row they are looking at instead of
 * reading a banner about it. `walletBalance` is what the bill quoted; the
 * binding check happens again under the member's row lock, because between the
 * quote and the write another terminal may have spent it.
 */
export function validateSplits(
  splits: readonly PaymentSplitDraft[],
  due: number,
  walletBalance = 0,
): SplitIssues {
  const rows = effectiveSplits(splits, due);
  const unreadable = rows.filter((row) => draftAmount(row) === null).map((row) => row.method);
  const tendered = tenderedTotal(rows);
  const remainder = due - tendered;

  const missingRef = rows
    .filter((row) => isMfs(row.method) && (draftAmount(row) ?? 0) > 0)
    .filter((row) => row.paymentRef.trim() === '')
    .map((row) => row.method);

  const wallet = rows
    .filter((row) => row.method === 'WALLET')
    .reduce((sum, row) => sum + (draftAmount(row) ?? 0), 0);

  const balanced = unreadable.length === 0 && remainder === 0;
  const walletOver = wallet > walletBalance;

  return {
    tendered,
    remainder,
    balanced,
    unreadable,
    missingRef,
    walletOver,
    ok: balanced && missingRef.length === 0 && !walletOver,
  };
}

/* ------------------------------------------------------------ the request */

export type SettleBodyInput = {
  /** The station session behind the bill, when there is one. */
  sessionId?: number | null;
  /** The counter cart, when the bill opened one. */
  cartId?: number | null;
  splits: readonly PaymentSplitDraft[];
  due: number;
  redeemPoints?: number;
  /** Draft entry lines, still carrying their quantity. */
  entries?: readonly { tournamentId: number; qty: number }[];
  /** Draft ticket lines, still carrying their quantity. */
  tickets?: readonly { consoleType: 'PS5' | 'PS4'; blocks: number; qty: number }[];
  /** The name printed on every stub this settle produces. */
  playerName?: string;
};

/**
 * The `POST /payments` body.
 *
 * Three things happen here and nowhere else: the draft's `qty` is expanded into
 * one request line per stub (two entries on one tournament are two players with
 * two seeds, not a line with a quantity), zero-amount tenders are dropped
 * because `payment_splits` refuses them, and the target is left empty for a
 * walk-up with neither a seat nor a basket.
 */
export function settleBody(input: SettleBodyInput): SettleRequest {
  const rows = effectiveSplits(input.splits, input.due);
  const splits = rows
    .map((row) => ({
      method: row.method,
      amount: draftAmount(row) ?? 0,
      paymentRef: row.paymentRef.trim() === '' ? undefined : row.paymentRef.trim(),
    }))
    .filter((split) => split.amount !== 0);

  const target: SettleRequest['target'] = {};
  if (typeof input.sessionId === 'number') target.sessionId = input.sessionId;
  else if (typeof input.cartId === 'number') target.cartId = input.cartId;

  const body: SettleRequest = { target, splits };

  if ((input.redeemPoints ?? 0) > 0) body.redeemPoints = input.redeemPoints;

  const entries = repeat(input.entries ?? [], (entry) => ({
    tournamentId: entry.tournamentId,
    playerName: input.playerName,
  }));
  if (entries.length > 0) body.tournamentEntries = entries;

  const tickets = repeat(input.tickets ?? [], (ticket) => ({
    consoleType: ticket.consoleType,
    blocks: ticket.blocks,
    playerName: input.playerName,
  }));
  if (tickets.length > 0) body.playTickets = tickets;

  return body;
}

/** One output row per unit of quantity — a stub is a stub, not a line item. */
function repeat<T extends { qty: number }, R>(lines: readonly T[], made: (line: T) => R): R[] {
  const out: R[] = [];
  for (const line of lines) {
    for (let i = 0; i < Math.max(0, Math.trunc(line.qty)); i += 1) out.push(made(line));
  }
  return out;
}
