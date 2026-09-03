'use client';

/**
 * The signed-in operator's own preference — `GET /me/prefs` (design.md §6,
 * "Profile · avatar color · per staff login").
 *
 * It is a query and not part of the session because it is the one thing on
 * S13 that outlives a terminal: the colour follows the person to whichever
 * counter they sign in at, and any role may read and write their own. The
 * session context still carries a copy for the sidebar avatar — it arrives
 * with the login response — and S13 updates both when the swatch moves.
 */

import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { queryKeys } from '@/lib/query-keys';
import type { Prefs } from './schemas';

export function prefsQueryOptions() {
  return {
    queryKey: queryKeys.prefs.me(),
    queryFn: () => api.get<Prefs>('/me/prefs'),
    staleTime: 60_000,
  };
}

export function usePrefs(options: { enabled?: boolean } = {}) {
  return useQuery({ ...prefsQueryOptions(), enabled: options.enabled ?? true });
}
