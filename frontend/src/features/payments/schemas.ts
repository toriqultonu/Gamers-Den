/**
 * Payment shapes — docs/api-contract.md (Billing & payments).
 *
 * These schemas guard the *form*, not the server. The server prices and settles
 * and is always authoritative; validating here means the operator sees "enter
 * the TrxID" under the field instead of a 409 banner after the tender is typed.
 * The two rules mirrored client-side are the ones with a domain code behind
 * them: `SPLIT_MISMATCH` and `PAYMENT_REF_REQUIRED`.
 */

import { z } from 'zod';
import type { Schemas } from '@/lib/api';

/** The four tenders the drawer knows (`PaymentSplit.method`). */
export const PAYMENT_METHODS = ['CASH', 'BKASH', 'NAGAD', 'WALLET'] as const;
export type PaymentMethod = (typeof PAYMENT_METHODS)[number];

/** bKash and Nagad are recorded manually — a TrxID is the only proof we hold. */
export const MFS_METHODS: readonly PaymentMethod[] = ['BKASH', 'NAGAD'];

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

/** A settle targets exactly one of a station session or a counter cart. */
export const settleTargetSchema = z
  .object({
    sessionId: z.int().positive().optional(),
    cartId: z.int().positive().optional(),
  })
  .refine((target) => Boolean(target.sessionId) !== Boolean(target.cartId), {
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

export const settleRequestSchema = z.object({
  target: settleTargetSchema,
  splits: z.array(paymentSplitSchema).min(1),
  redeemPoints: z.int().nonnegative().optional(),
  playTickets: z.array(playTicketLineSchema).optional(),
  tournamentEntries: z.array(tournamentEntryLineSchema).optional(),
});

export type SettleRequestInput = z.infer<typeof settleRequestSchema>;

/** The generated request shape this form must still fit. */
export type SettleRequest = Schemas['SettleRequest'];

/** What the splits add up to — the number `SPLIT_MISMATCH` compares against. */
export function splitTotal(splits: readonly { amount?: number }[]): number {
  return splits.reduce((sum, split) => sum + (split.amount ?? 0), 0);
}

/**
 * The client-side half of `SPLIT_MISMATCH`: the tender must equal what is due
 * (points redeemed count toward it). The server re-checks and wins; this only
 * keeps the Settle button honest.
 */
export function splitsBalance(
  splits: readonly { amount?: number }[],
  amountDue: number,
  redeemPoints = 0,
): boolean {
  return splitTotal(splits) + redeemPoints === amountDue;
}
