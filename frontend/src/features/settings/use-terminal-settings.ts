'use client';

/**
 * `['terminal-settings']` — theme, text size, accent, sound, auto-lock,
 * receipt copies and the S1 background image (design.md §6).
 *
 * F04 reads it for one field: `autoLockMin` drives the shell's idle lock; F15
 * added the screen that writes them (`features/settings/mutations.ts`) and
 * {@link useAppliedAppearance}, which paints the terminal's saved theme on
 * whatever screen it signs in on.
 */

import { useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api, type Schemas } from '@/lib/api';
import { queryKeys } from '@/lib/query-keys';
import { appearanceOf, applyAppearance, cacheAppearance } from './appearance';

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

/**
 * Paints the terminal's saved appearance, and refreshes the cache the
 * pre-paint script reads (frontend/ARCHITECTURE.md §5.5).
 *
 * Mounted once in the shell rather than in S13, because a terminal whose row
 * says "light" must be light on the Floor as well — and because the cache is
 * only a hint: this is what repairs it on a terminal that has been reimaged,
 * or configured from a different browser profile.
 *
 * It does nothing until the settings have actually arrived. Applying defaults
 * while the query is pending would undo the very flash the inline script
 * exists to prevent.
 */
export function useAppliedAppearance(settings: TerminalSettings | undefined): void {
  const theme = settings?.theme;
  const fontScale = settings?.fontScale;
  const accent = settings?.accent;
  const loginBgImageId = settings?.loginBgImageId ?? null;

  useEffect(() => {
    if (!theme && !fontScale && !accent) return;
    const appearance = appearanceOf({ theme, fontScale, accent } as TerminalSettings);
    applyAppearance(appearance);
    cacheAppearance(appearance, loginBgImageId);
  }, [theme, fontScale, accent, loginBgImageId]);
}
