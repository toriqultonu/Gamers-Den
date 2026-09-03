/**
 * S13 shapes — design.md §6's table, and the closed sets the API checks
 * (`TerminalSettingsService`: autoLockMin ∈ {0,2,5,10}, receiptCopies ∈ {1,2},
 * accent ∈ the three swatch hexes).
 *
 * The draft is the whole object because `PUT /terminal-settings` is a replace,
 * not a patch: "the whole object; every field is required except
 * loginBgImageId". S13 holds every control's value on screen anyway, so the
 * draft *is* the screen, and the request is a pure function of it — which is
 * what makes "changing the theme also saves the auto-lock you set a second
 * ago" true by construction rather than by remembering to merge.
 */

import { z } from 'zod';
import type { Role } from '@/lib/nav';
import type { Schemas } from '@/lib/api';
import {
  ACCENT_HEX,
  THEME_TO_API,
  TEXT_SIZE_TO_API,
  accentFromHex,
  themeFromApi,
  textSizeFromApi,
  type Appearance,
} from './appearance';
import { ACCENTS, TEXT_SIZES, THEMES } from '@/styles/tokens';

export type TerminalSettings = Schemas['TerminalSettings'];
export type UpdateTerminalSettingsRequest = Schemas['UpdateTerminalSettingsRequest'];

/* --------------------------------------------------------------- who writes */

/**
 * "Admin write; any role reads" (api-contract.md, Settings). Every operator
 * opens S13 — the sidebar shows it to all three roles — but only the owner
 * moves the terminal's appearance, so a cashier's controls are read-only and
 * the API 403s regardless (frontend/ARCHITECTURE.md §4.3).
 */
export function canEditTerminalSettings(role: Role | null | undefined): boolean {
  return role === 'ADMIN';
}

/** The profile swatch is the one control on this screen that is everyone's. */
export function canEditOwnPrefs(role: Role | null | undefined): boolean {
  return role !== null && role !== undefined;
}

/* ------------------------------------------------------------- the choices */

/** design.md §6: "Off / 2 / 5 / 10 min" — `0` is off. */
export const AUTO_LOCK_CHOICES = [0, 2, 5, 10] as const;
export type AutoLockChoice = (typeof AUTO_LOCK_CHOICES)[number];

/** design.md §6: "Receipt copies · 1 / 2". */
export const RECEIPT_COPY_CHOICES = [1, 2] as const;
export type ReceiptCopies = (typeof RECEIPT_COPY_CHOICES)[number];

export function autoLockLabel(minutes: number): string {
  return minutes === 0 ? 'Off' : `${minutes} min`;
}

/* ----------------------------------------------------------------- the draft */

export type SettingsDraft = Appearance & {
  sound: boolean;
  autoLockMin: number;
  receiptCopies: number;
  loginBgImageId: string | null;
};

/**
 * What a terminal that has never been configured shows, spelled the same way
 * the backend spells it (`TerminalSettingsService`: dark, default, Den Red, no
 * background, sound on, 5-minute auto-lock, 1 copy).
 */
export const DEFAULT_SETTINGS_DRAFT: SettingsDraft = {
  theme: 'dark',
  textSize: 'default',
  accent: 'red',
  sound: true,
  autoLockMin: 5,
  receiptCopies: 1,
  loginBgImageId: null,
};

export function settingsDraft(settings: TerminalSettings | undefined | null): SettingsDraft {
  if (!settings) return DEFAULT_SETTINGS_DRAFT;
  return {
    theme: themeFromApi(settings.theme),
    textSize: textSizeFromApi(settings.fontScale),
    accent: accentFromHex(settings.accent),
    sound: settings.sound ?? DEFAULT_SETTINGS_DRAFT.sound,
    autoLockMin: settings.autoLockMin ?? DEFAULT_SETTINGS_DRAFT.autoLockMin,
    receiptCopies: settings.receiptCopies ?? DEFAULT_SETTINGS_DRAFT.receiptCopies,
    loginBgImageId: settings.loginBgImageId ?? null,
  };
}

/** The three attributes the document is painted from, out of the draft. */
export function draftAppearance(draft: SettingsDraft): Appearance {
  return { theme: draft.theme, textSize: draft.textSize, accent: draft.accent };
}

/**
 * The closed sets, mirroring the service's own validation so a bad value is
 * caught before it costs a round trip — and so the test can assert the sets
 * without booting a backend.
 */
export const settingsDraftSchema = z.object({
  theme: z.enum(THEMES),
  textSize: z.enum(TEXT_SIZES),
  accent: z.enum(ACCENTS),
  sound: z.boolean(),
  autoLockMin: z.union([z.literal(0), z.literal(2), z.literal(5), z.literal(10)]),
  receiptCopies: z.union([z.literal(1), z.literal(2)]),
  loginBgImageId: z.string().min(1).nullable(),
});

/** Draft → `PUT /terminal-settings` body. */
export function toUpdateRequest(draft: SettingsDraft): UpdateTerminalSettingsRequest {
  return {
    theme: THEME_TO_API[draft.theme],
    fontScale: TEXT_SIZE_TO_API[draft.textSize],
    accent: ACCENT_HEX[draft.accent],
    loginBgImageId: draft.loginBgImageId,
    sound: draft.sound,
    autoLockMin: draft.autoLockMin,
    receiptCopies: draft.receiptCopies,
  };
}

/* ---------------------------------------------------------------- profile */

export type Prefs = Schemas['PrefsResponse'];

/** `PUT /me/prefs` — `#rrggbb` or null to reset (backend `PrefsRequest`). */
export const avatarColorSchema = z
  .string()
  .regex(/^#[0-9a-fA-F]{6}$/, 'Pick one of the six swatches.')
  .nullable();
