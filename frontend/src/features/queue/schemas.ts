/**
 * Play-queue shapes — docs/bookings.md §3–4, api-contract.md (Play queue).
 *
 * A play ticket is prepaid time with a daily token on it, sellable while every
 * console is busy. Seating is where the one rule with teeth lives: a PS5 ticket
 * cannot be seated on a PS4 (409 `CONSOLE_TYPE_MISMATCH`), so the rail can grey
 * the action out before the operator finds out the hard way.
 */

import { z } from 'zod';
import type { Schemas } from '@/lib/api';
import { paymentMethodSchema } from '@/features/payments/schemas';

/** `stations.console_type` / `queue_entries.console_type`. */
export const CONSOLE_TYPES = ['PS5', 'PS4'] as const;
export const consoleTypeSchema = z.enum(CONSOLE_TYPES);
export type ConsoleType = (typeof CONSOLE_TYPES)[number];

/** `queue_entries.status` (DDL). */
export const QUEUE_STATUSES = ['WAITING', 'SEATED', 'REFUNDED'] as const;
export type QueueStatus = (typeof QUEUE_STATUSES)[number];

/** Where a token came from — both share one daily counter. */
export const QUEUE_SOURCES = ['BOOKING', 'PLAY_TICKET'] as const;
export type QueueSource = (typeof QUEUE_SOURCES)[number];

export type QueueEntry = Schemas['QueueEntry'];

/** The POS play-ticket line: console type + length, paid on the spot. */
export const sellPlayTicketSchema = z
  .object({
    consoleType: consoleTypeSchema,
    blocks: z.int().min(1).max(48),
    playerName: z.string().trim().max(80).optional(),
    method: paymentMethodSchema,
    paymentRef: z.string().trim().max(64).optional(),
  })
  .refine(
    (ticket) =>
      (ticket.method !== 'BKASH' && ticket.method !== 'NAGAD') || Boolean(ticket.paymentRef),
    { error: 'Enter the bKash/Nagad TrxID.', path: ['paymentRef'] },
  );

export type SellPlayTicketInput = z.infer<typeof sellPlayTicketSchema>;

export const seatQueueEntrySchema = z.object({
  stationId: z.int().positive(),
});

/**
 * Can this waiting token be seated on that console?
 *
 * The console must be free and of the ticket's own type — the client-side twin
 * of `STATION_BUSY` and `CONSOLE_TYPE_MISMATCH`. The rail disables the seat
 * action when nothing qualifies ("no free console of that type"); the server
 * still refuses, this only keeps the button honest.
 */
export function canSeatOn(
  entry: Pick<QueueEntry, 'consoleType' | 'status'>,
  station: { consoleType?: string; floorState?: string },
): boolean {
  if (entry.status !== 'WAITING') return false;
  if (station.consoleType !== entry.consoleType) return false;
  return station.floorState === 'FREE';
}

/** WAITING entries in token order — "who plays next" on the Floor rail. */
export function waitingInTokenOrder(entries: readonly QueueEntry[]): QueueEntry[] {
  return entries
    .filter((entry) => entry.status === 'WAITING')
    .slice()
    .sort((a, b) => {
      const byDate = (a.tokenDate ?? '').localeCompare(b.tokenDate ?? '');
      return byDate !== 0 ? byDate : (a.tokenNo ?? 0) - (b.tokenNo ?? 0);
    });
}
