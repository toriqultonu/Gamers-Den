/**
 * Session shapes — docs/api-contract.md (Sessions), backend §5.1.
 *
 * The session is the only object with a clock, so this is also where the
 * server's reading is turned into the {@link ClockSnapshot} every countdown
 * ticks from (frontend/ARCHITECTURE.md §5.2).
 */

import { z } from 'zod';
import type { Schemas } from '@/lib/api';
import { serverOffsetMs, type ClockSnapshot } from '@/lib/time';

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

/* ------------------------------------------------------- the floor's cards */

/**
 * A StationCard's variant, straight off `Station.floorState`.
 *
 * The eight design.md §2 variants and the eight server states are the same
 * eight things under different names, so this is a rename, not a decision —
 * which is the point: the card never second-guesses what the floor is.
 */
export const STATION_VARIANTS = [
  'free',
  'open',
  'active',
  'paused',
  'locked',
  'reserved',
  'booked',
  'maintenance',
] as const;
export type StationVariant = (typeof STATION_VARIANTS)[number];

const VARIANT_BY_FLOOR_STATE: Record<FloorState, StationVariant> = {
  FREE: 'free',
  OPEN: 'open',
  RUNNING: 'active',
  PAUSED: 'paused',
  LOCKED: 'locked',
  RESERVED: 'reserved',
  BOOKED: 'booked',
  MAINTENANCE: 'maintenance',
};

export function stationVariant(station: Pick<Station, 'floorState'>): StationVariant {
  return VARIANT_BY_FLOOR_STATE[(station.floorState ?? 'FREE') as FloorState] ?? 'free';
}

/** A console nobody is on and nothing is holding — the only seatable state. */
export function isSeatable(station: Pick<Station, 'floorState'>): boolean {
  const variant = stationVariant(station);
  return variant === 'free' || variant === 'booked';
}

/**
 * The card's clock reading.
 *
 * `Station` carries no `serverTime` of its own — the list is one snapshot of
 * the whole floor — so the reading is dated by *when the response landed*,
 * converted into server time with the offset `lib/api.ts` measures from every
 * `Date` header. Pass `receivedAt` as the query's `dataUpdatedAt`; the result
 * is the same instant the server was describing, expressed the way
 * `remainingSecondsNow` wants it.
 *
 * A reserved console with a started match ticks its **match** clock here — the
 * floor shows it like any other session (docs/tournaments.md §4).
 */
export function stationClockSnapshot(
  station: Pick<Station, 'session' | 'match' | 'floorState'>,
  receivedAt: number,
): ClockSnapshot | null {
  const asOf = receivedAt + serverOffsetMs();

  if (station.match && typeof station.match.remainingSeconds === 'number') {
    return {
      remainingSeconds: station.match.remainingSeconds,
      asOf,
      // A started match runs on the server's clock whatever the console does.
      running: true,
    };
  }

  const session = station.session;
  if (!session) return null;
  return {
    remainingSeconds: session.remainingSeconds ?? 0,
    asOf,
    running: session.state === 'RUNNING',
  };
}
