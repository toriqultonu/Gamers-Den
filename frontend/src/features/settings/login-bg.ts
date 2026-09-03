'use client';

/**
 * The optional photograph behind S1 (design.md §1, §6, §7 — "the only
 * photograph is the optional login background, shown under a dark overlay").
 *
 * The picture is public (`GET /terminal-settings/login-bg/{imageId}` is one of
 * the few unauthenticated routes) but the id that names it lives in
 * `GET /terminal-settings`, which is not. Nobody is signed in on S1, so the
 * terminal reads the id out of the appearance cache it already keeps for the
 * no-flash theme script; F14 writes it there when the owner picks the image.
 */

import { API_BASE_URL } from '@/lib/api';
import { APPEARANCE_CACHE_KEY, type AppearanceCache } from '@/styles/tokens';

/** The public image route — SecurityConfig's `LOGIN_BG_PATH`. */
export function loginBgUrl(imageId: string): string {
  return `${API_BASE_URL}/terminal-settings/login-bg/${encodeURIComponent(imageId)}`;
}

/** The cached image id, or null on a terminal that has never had one set. */
export function readCachedLoginBgId(): string | null {
  try {
    if (typeof window === 'undefined') return null;
    const raw = window.localStorage.getItem(APPEARANCE_CACHE_KEY);
    if (!raw) return null;
    const cache = JSON.parse(raw) as AppearanceCache;
    return typeof cache.loginBgImageId === 'string' && cache.loginBgImageId
      ? cache.loginBgImageId
      : null;
  } catch {
    return null;
  }
}
