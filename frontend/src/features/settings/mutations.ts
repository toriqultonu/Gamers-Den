'use client';

/**
 * S13's writes: the terminal's settings, its login background, and the
 * operator's own swatch.
 *
 * None of them is money and none of them takes an `Idempotency-Key` — the
 * guarded route list (api-contract.md §1) does not name them, and a replayed
 * `PUT` of a replace-the-whole-object body sets exactly what it set the first
 * time.
 *
 * What they *are* is instant. design.md §6 wants theme, text size and accent
 * "applied instantly from local state", so the screen repaints from its own
 * draft before these hooks are called and these hooks only make it survive a
 * reload. That is the opposite of optimistic: nothing here draws a state the
 * server has not accepted — the paint is local by design, and the persisted
 * row is whatever the server answers with, written straight into
 * `['terminal-settings']` so the shell's auto-lock and S1's background read
 * the same object.
 */

import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { queryKeys } from '@/lib/query-keys';
import { appearanceOf, cacheAppearance } from './appearance';
import type { Prefs, SettingsDraft, TerminalSettings } from './schemas';
import { toUpdateRequest } from './schemas';

/**
 * `PUT /terminal-settings` (Admin) — the whole object.
 *
 * The answer is the stored row, and it is also what the next first paint has
 * to agree with, so it lands in two places at once: the query cache, and the
 * localStorage hint the no-flash script reads (frontend/ARCHITECTURE.md §5.5).
 * A refusal writes neither — the terminal keeps painting the operator's choice
 * for this session, and the notice says it was not saved.
 */
export function useUpdateTerminalSettings() {
  const client = useQueryClient();
  return useMutation<TerminalSettings, unknown, SettingsDraft>({
    mutationFn: (draft) => api.put<TerminalSettings>('/terminal-settings', toUpdateRequest(draft)),
    onSuccess: (settings) => {
      client.setQueryData(queryKeys.terminalSettings.all(), settings);
      cacheAppearance(appearanceOf(settings), settings.loginBgImageId ?? null);
    },
  });
}

/**
 * `POST /terminal-settings/login-bg` (Admin) — multipart, part name `file`.
 *
 * The upload stores the picture and returns its id; attaching that id to the
 * terminal is the caller's next `PUT`, which is why this hook does not touch
 * the cache. `lib/api.ts` passes `FormData` through untouched, so the browser
 * sets its own multipart boundary.
 */
export function useUploadLoginBg() {
  return useMutation<{ loginBgImageId?: string }, unknown, { file: File }>({
    mutationFn: ({ file }) => {
      const form = new FormData();
      form.append('file', file);
      return api.post<{ loginBgImageId?: string }>('/terminal-settings/login-bg', form);
    },
  });
}

/** `PUT /me/prefs` (any role) — `null` resets to the default swatch. */
export function useUpdatePrefs() {
  const client = useQueryClient();
  return useMutation<Prefs, unknown, { avatarColor: string | null }>({
    mutationFn: (body) => api.put<Prefs>('/me/prefs', body),
    onSuccess: (prefs) => {
      client.setQueryData(queryKeys.prefs.me(), prefs);
    },
  });
}
