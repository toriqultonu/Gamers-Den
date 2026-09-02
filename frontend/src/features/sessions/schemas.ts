/**
 * Session shapes — docs/api-contract.md (Sessions), backend §5.1.
 *
 * The session is the only object with a clock, so this is also where the
 * server's reading is turned into the {@link ClockSnapshot} every countdown
 * ticks from (frontend/ARCHITECTURE.md §5.2).
 */

import { z } from 'zod';
import type { Schemas } from '@/lib/api';
import type { ClockSnapshot } from '@/lib/time';

/** `OPEN → RUNNING ⇄ PAUSED → LOCKED → CLOSED`. */
export const SESSION_STATES = ['OPEN', 'RUNNING', 'PAUSED', 'LOCKED', 'CLOSED'] as const;
export type SessionState = (typeof SESSION_STATES)[number];

/** What a StationCard can be showing (`Station.floorState`). */
export const FLOOR_STATES = [
  'FREE',
  'OPEN',
  'RUNNING',
  'PAUSED',
  'LOCKED',
  'RESERVED',
  'BOOKED',
  'MAINTENANCE',
] as const;
export type FloorState = (typeof FLOOR_STATES)[number];

export type Session = Schemas['Session'];
export type Station = Schemas['Station'];

/** `POST /sessions` — a walk-in, or a seat that loads prepaid blocks as paid. */
export const createSessionSchema = z
  .object({
    stationId: z.int().positive(),
    memberId: z.int().positive().optional(),
    bookingId: z.int().positive().optional(),
    queueEntryId: z.int().positive().optional(),
  })
  .refine((input) => !(input.bookingId && input.queueEntryId), {
    error: 'A seat comes from a booking or a queue token, never both.',
  });

/** `POST /sessions/{id}/blocks` — one 30-minute block at a time, either way. */
export const blocksSchema = z.object({
  delta: z.union([z.literal(1), z.literal(-1)]),
});

export const clockActionSchema = z.object({
  action: z.enum(['START', 'PAUSE', 'RESUME']),
});

/**
 * The server's clock reading, ready for `remainingSecondsNow`.
 *
 * Only a RUNNING session drains: OPEN, PAUSED and LOCKED hold their reading, so
 * a paused console shows the same number a minute later. `serverTime` is the
 * instant the reading was true — the local clock is never consulted.
 */
export function clockSnapshot(
  session: Pick<Session, 'remainingSeconds' | 'state' | 'serverTime'>,
  fallbackAsOf: number,
): ClockSnapshot {
  return {
    remainingSeconds: session.remainingSeconds ?? 0,
    asOf: session.serverTime ?? fallbackAsOf,
    running: session.state === 'RUNNING',
  };
}

/**
 * Net outstanding — unpaid blocks plus the unsettled cart. `POST /end` refuses
 * with `SESSION_HAS_BALANCE` while this is positive; prepaid blocks already
 * count as settled, which is why a seated booking ends without a payment.
 */
export function hasBalance(session: Pick<Session, 'netOutstanding'>): boolean {
  return (session.netOutstanding ?? 0) > 0;
}
