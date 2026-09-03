'use client';

/**
 * `['terminal-settings']` — theme, text size, accent, sound, auto-lock,
 * receipt copies and the S1 background image (design.md §6).
 *
 * F04 reads it for one field: `autoLockMin` drives the shell's idle lock. The
 * writes and the S13 screen that drives them arrive in F14; the query lives
 * here now so both use the same key and the same parse.
 */

import { useQuery } from '@tanstack/react-query';
import { api, type Schemas } from '@/lib/api';
import { queryKeys } from '@/lib/query-keys';

export type TerminalSettings = Schemas['TerminalSettings'];

/** S13: Off / 2 / 5 / 10 minutes — `0` is off. */
export const DEFAULT_AUTO_LOCK_MIN = 0;

export function terminalSettingsQueryOptions() {
  return {
    queryKey: queryKeys.terminalSettings.all(),
    queryFn: () => api.get<TerminalSettings>('/terminal-settings'),
    staleTime: 60_000,
  };
}

export function useTerminalSettings(options: { enabled?: boolean } = {}) {
  return useQuery({ ...terminalSettingsQueryOptions(), enabled: options.enabled ?? true });
}

/** Minutes of idle before the shell locks; `0` while unknown or switched off. */
export function autoLockMinutes(settings: TerminalSettings | undefined): number {
  const minutes = settings?.autoLockMin;
  return typeof minutes === 'number' && minutes > 0 ? minutes : DEFAULT_AUTO_LOCK_MIN;
}
