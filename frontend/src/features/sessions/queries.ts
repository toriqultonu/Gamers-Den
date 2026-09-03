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
