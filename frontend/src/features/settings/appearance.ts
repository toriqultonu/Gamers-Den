/**
 * Appearance — the S13 controls that repaint the app, and the only place the
 * server's spelling of them meets the tokens' (design.md §6, §3).
 *
 * `GET /terminal-settings` answers in the API's vocabulary — `theme: "DARK"`,
 * `fontScale: "LARGE"`, `accent: "#0f62fe"` — while `styles/tokens.css` keys
 * off `data-theme="dark" data-text-size="large" data-accent="blue"`. The
 * translation is written once here because three things have to agree on it:
 * S13's controls, the cache the terminal keeps, and the inline script that
 * paints before React exists.
 *
 * Two invariants live in this file:
 *
 *  - **§5.5, theme before first paint.** {@link noFlashScript} is what
 *    `app/layout.tsx` inlines in `<head>`: it reads the terminal's cached
 *    appearance and stamps the attributes synchronously, so a light-theme
 *    terminal never flashes dark on the way to its own settings. That is also
 *    why the cache exists at all — the settings live behind a token, and the
 *    first paint happens before there is one.
 *  - **§5.5, persist via `PUT /terminal-settings`.** The cache is a paint
 *    hint, never the source of truth: the server row is, and every write goes
 *    there first ({@link ../settings/mutations}).
 *
 * No `'use client'` and no hooks on purpose — `app/layout.tsx` is a server
 * component and imports {@link noFlashScript} from here.
 */

import {
  ACCENTS,
  ACCENT_BASE,
  ACCENT_LABELS,
  APPEARANCE_CACHE_KEY,
  DEFAULT_ACCENT,
  DEFAULT_TEXT_SIZE,
  DEFAULT_THEME,
  TEXT_SIZES,
  THEMES,
  type Accent,
  type AppearanceCache,
  type TextSize,
  type Theme,
} from '@/styles/tokens';
import type { Schemas } from '@/lib/api';

export type TerminalSettings = Schemas['TerminalSettings'];

/** What the API calls a theme, and what the tokens call one. */
export type ApiTheme = 'DARK' | 'LIGHT';
export type ApiFontScale = 'COMPACT' | 'DEFAULT' | 'LARGE';

export const THEME_TO_API: Record<Theme, ApiTheme> = { dark: 'DARK', light: 'LIGHT' };
export const TEXT_SIZE_TO_API: Record<TextSize, ApiFontScale> = {
  compact: 'COMPACT',
  default: 'DEFAULT',
  large: 'LARGE',
};

/** The three swatches S13 offers, hex-for-hex with the backend's `Accent`. */
export const ACCENT_HEX: Record<Accent, string> = ACCENT_BASE;

export const THEME_LABELS: Record<Theme, string> = { dark: 'Dark', light: 'Light' };
export const TEXT_SIZE_LABELS: Record<TextSize, string> = {
  compact: 'Compact',
  default: 'Default',
  large: 'Large',
};
export { ACCENT_LABELS };

export function themeFromApi(value: string | undefined): Theme {
  const theme = (value ?? '').toLowerCase() as Theme;
  return THEMES.includes(theme) ? theme : DEFAULT_THEME;
}

export function textSizeFromApi(value: string | undefined): TextSize {
  const size = (value ?? '').toLowerCase() as TextSize;
  return TEXT_SIZES.includes(size) ? size : DEFAULT_TEXT_SIZE;
}

/**
 * Hex → swatch. The column is TEXT and the closed set lives in the backend's
 * `Accent` enum, so an unknown hex is a terminal configured by a newer build:
 * it falls back to Den Red rather than painting an accent with no tonal ramp
 * behind it (which is what would break the contrast rules design.md §3 fixes).
 */
export function accentFromHex(value: string | undefined | null): Accent {
  const hex = (value ?? '').trim().toLowerCase();
  return ACCENTS.find((accent) => ACCENT_HEX[accent] === hex) ?? DEFAULT_ACCENT;
}

/* ------------------------------------------------------------- appearance */

/** The three attributes `styles/tokens.css` cascades from. */
export type Appearance = {
  theme: Theme;
  textSize: TextSize;
  accent: Accent;
};

export const DEFAULT_APPEARANCE: Appearance = {
  theme: DEFAULT_THEME,
  textSize: DEFAULT_TEXT_SIZE,
  accent: DEFAULT_ACCENT,
};

/** The terminal's row, read as an appearance. Defaults fill anything absent. */
export function appearanceOf(settings: TerminalSettings | undefined | null): Appearance {
  if (!settings) return DEFAULT_APPEARANCE;
  return {
    theme: themeFromApi(settings.theme),
    textSize: textSizeFromApi(settings.fontScale),
    accent: accentFromHex(settings.accent),
  };
}

/**
 * Paint it. This is the "instantly" in design.md §6 — S13 calls it from the
 * control's own handler, so the app is already repainted while the `PUT` is
 * still in flight, and the settings screen never waits on the network to show
 * what a choice looks like.
 */
export function applyAppearance(appearance: Appearance, root?: HTMLElement): void {
  const element = root ?? (typeof document === 'undefined' ? null : document.documentElement);
  if (!element) return;
  element.dataset.theme = appearance.theme;
  element.dataset.textSize = appearance.textSize;
  element.dataset.accent = appearance.accent;
}

export function sameAppearance(a: Appearance, b: Appearance): boolean {
  return a.theme === b.theme && a.textSize === b.textSize && a.accent === b.accent;
}

/* ------------------------------------------------------------------ cache */

/** The paint hint the inline script reads. Never authoritative. */
export function readAppearanceCache(): AppearanceCache {
  try {
    if (typeof window === 'undefined') return {};
    const raw = window.localStorage.getItem(APPEARANCE_CACHE_KEY);
    return raw ? ((JSON.parse(raw) ?? {}) as AppearanceCache) : {};
  } catch {
    // A terminal with storage disabled still runs; it just flashes the default
    // theme for one frame after a reload.
    return {};
  }
}

/** Merges — S1's background id and the three attributes are written apart. */
export function writeAppearanceCache(patch: AppearanceCache): void {
  try {
    if (typeof window === 'undefined') return;
    const next = { ...readAppearanceCache(), ...patch };
    window.localStorage.setItem(APPEARANCE_CACHE_KEY, JSON.stringify(next));
  } catch {
    // Same story: a cache miss costs a flash, never a wrong setting.
  }
}

/** Everything the next first paint needs: the attributes and S1's photograph. */
export function cacheAppearance(
  appearance: Appearance,
  loginBgImageId?: string | null,
): void {
  writeAppearanceCache({ ...appearance, loginBgImageId: loginBgImageId ?? null });
}

/* -------------------------------------------------------- no-flash script */

/**
 * The pre-paint script `app/layout.tsx` inlines in `<head>` (§5.5).
 *
 * Synchronous, dependency-free and total: it validates every value it reads,
 * so a corrupted cache leaves the server-rendered defaults (dark · Den Red ·
 * default) exactly where they are rather than stamping nonsense on `<html>`.
 */
export function noFlashScript(): string {
  return (
    `(function(){try{` +
    `var s=JSON.parse(localStorage.getItem(${JSON.stringify(APPEARANCE_CACHE_KEY)})||'{}');` +
    `var r=document.documentElement;` +
    `if(${jsIncludes(THEMES)}.indexOf(s.theme)>-1)r.dataset.theme=s.theme;` +
    `if(${jsIncludes(ACCENTS)}.indexOf(s.accent)>-1)r.dataset.accent=s.accent;` +
    `if(${jsIncludes(TEXT_SIZES)}.indexOf(s.textSize)>-1)r.dataset.textSize=s.textSize;` +
    `}catch(e){}})();`
  );
}

function jsIncludes(values: readonly string[]): string {
  return JSON.stringify(values);
}
