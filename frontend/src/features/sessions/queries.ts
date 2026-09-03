'use client';

/**
 * Session + station reads: `['sessions']`, `['sessions', id]`, `['stations']`.
 *
 * F04 needs the station list for the topbar's occupancy counter; the session
 * detail, bill and clock reads that S3 runs on are F06. The key is the
 * canonical one, so F05's `station-update` handler writes straight into what
 * the topbar is already reading.
 */

import { useQuery } from '@tanstack/react-query';
import { api, type Schemas } from '@/lib/api';
import { queryKeys } from '@/lib/query-keys';
import { isSeatable } from './schemas';

export type Station = Schemas['Station'];

/** `GET /stations` — every console with its live session/match/arrival summary. */
export function stationsQueryOptions() {
  return {
    queryKey: queryKeys.stations.all(),
    queryFn: () => api.get<Station[]>('/stations'),
  };
}

export function useStations(options: { enabled?: boolean } = {}) {
  return useQuery({ ...stationsQueryOptions(), enabled: options.enabled ?? true });
}

export type Occupancy = { busy: number; total: number };

/**
 * "Stations busy 2/4". Busy is a console someone is on or holding — a seat
 * blocked for a tournament counts, an empty or out-of-service one does not.
 */
const FREE_STATES = new Set(['FREE', 'MAINTENANCE']);

export function occupancyOf(stations: Station[] | undefined): Occupancy {
  const rows = stations ?? [];
  return {
    busy: rows.filter((station) => !FREE_STATES.has(station.floorState ?? 'FREE')).length,
    total: rows.length,
  };
}

/* ------------------------------------------------------- the panel's reads */

export type Session = Schemas['Session'];
export type Bill = Schemas['Bill'];

/**
 * `GET /sessions/{id}` — the full session behind the selected card.
 *
 * The card carries a summary; the panel needs the rest (paid vs unpaid blocks,
 * `netOutstanding`, the server's own `serverTime`). `station-update` marks this
 * key stale rather than writing it, so a live event lands here as a re-read of
 * the truth instead of a guess assembled from the summary (lib/sse.ts).
 */
export function sessionQueryOptions(id: number) {
  return {
    queryKey: queryKeys.sessions.detail(id),
    queryFn: () => api.get<Session>(`/sessions/${id}`),
  };
}

export function useSessionDetail(id: number | null | undefined) {
  return useQuery({
    ...sessionQueryOptions(id ?? 0),
    enabled: typeof id === 'number' && id > 0,
  });
}

/**
 * `GET /sessions/{id}/bill` — the running bill the panel prints down its side:
 * gaming (unbilled blocks only), food & drink, tournament lines, the prepaid
 * credit a seated booking arrives with, and the net that has to be zero before
 * the session can end.
 */
export function billQueryOptions(id: number) {
  return {
    queryKey: queryKeys.sessions.bill(id),
    queryFn: () => api.get<Bill>(`/sessions/${id}/bill`),
  };
}

export function useSessionBill(id: number | null | undefined) {
  return useQuery({
    ...billQueryOptions(id ?? 0),
    enabled: typeof id === 'number' && id > 0,
  });
}

/**
 * Consoles a token of this type can actually be seated on right now.
 *
 * The rail greys its seat action out when this is empty ("no free console of
 * that type") — the client-side twin of the server's `CONSOLE_TYPE_MISMATCH`
 * and `STATION_BUSY`, which still have the last word (docs/bookings.md §3).
 *
 * A console holding a checked-in arrival counts: it is empty, and
 * docs/bookings.md §7 explicitly allows seating a token on another free
 * console of the same type when a booking's own console is occupied. The
 * arrival keeps its place at the top of that console's panel either way.
 */
export function freeStationsOfType(
  stations: Station[] | undefined,
  consoleType: string | undefined,
): Station[] {
  if (!consoleType) return [];
  return (stations ?? []).filter(
    (station) => station.consoleType === consoleType && isSeatable(station),
  );
}
