'use client';

/**
 * The one read S10 owns that no other screen has: `['staff']`.
 *
 * Stations, pricing and the menu are already server state somebody else reads
 * — the floor draws `['stations']`, the POS draws `['items']` and `['pricing']`
 * — so S10 edits those through the same keys rather than forking a
 * configuration copy that would drift the moment a session started. The roster
 * is different: `GET /staff` is Admin-only, and no other screen may ask for it.
 */

import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { queryKeys } from '@/lib/query-keys';
import type { Staff } from './schemas';

export function staffQueryOptions() {
  return {
    queryKey: queryKeys.staff.all(),
    queryFn: () => api.get<Staff[]>('/staff'),
  };
}

/**
 * `GET /staff` — Admin only, so the query is mounted only when the screen has
 * drawn the staff section. A manager's S10 never fires it, and never collects
 * the 403 that a hidden-but-mounted query would.
 */
export function useStaff(options: { enabled?: boolean } = {}) {
  return useQuery({ ...staffQueryOptions(), enabled: options.enabled ?? true });
}

/** Active first, then alphabetical — a retired row is history, not a choice. */
export function staffRows(staff: Staff[] | undefined): Staff[] {
  return [...(staff ?? [])].sort((a, b) => {
    const active = Number(b.active ?? true) - Number(a.active ?? true);
    return active !== 0 ? active : (a.name ?? '').localeCompare(b.name ?? '');
  });
}
