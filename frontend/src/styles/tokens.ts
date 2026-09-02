/**
 * TypeScript mirror of `src/styles/tokens.css` — docs/design.md §3.
 *
 * The CSS file is the runtime source of truth; this module exists so the token
 * demo page (`/tokens`) can enumerate the system, and so a unit test can prove
 * the two never drift apart.
 */

export const THEMES = ['dark', 'light'] as const;
export type Theme = (typeof THEMES)[number];

export const ACCENTS = ['red', 'blue', 'green'] as const;
export type Accent = (typeof ACCENTS)[number];

export const TEXT_SIZES = ['compact', 'default', 'large'] as const;
export type TextSize = (typeof TEXT_SIZES)[number];

/** Dark is the default theme (dim venue); Den Red is the default accent. */
export const DEFAULT_THEME: Theme = 'dark';
export const DEFAULT_ACCENT: Accent = 'red';
export const DEFAULT_TEXT_SIZE: TextSize = 'default';

/**
 * Where the terminal's cached appearance settings live so the no-flash inline
 * script can apply them before first paint (frontend/ARCHITECTURE.md §5.5).
 * `PUT /terminal-settings` remains authoritative; F12 refreshes this cache.
 */
export const APPEARANCE_CACHE_KEY = 'gd.appearance';

export const ACCENT_LABELS: Record<Accent, string> = {
  red: 'Den Red',
  blue: 'Blue',
  green: 'Green',
};

export const RAMP_STEPS = [100, 200, 300, 400, 500, 600, 700, 800, 900] as const;
export type RampStep = (typeof RAMP_STEPS)[number];
type Ramp = Record<RampStep, string>;

/** Semantic surface / ink roles — docs/design.md §3 "Color". */
export const SURFACE_TOKENS: Record<Theme, Record<string, string>> = {
  dark: {
    bg: '#171514',
    surface: '#211f1e',
    card: '#2a2725',
    text: '#f3f2f2',
    divider: '#3c3835',
    track: '#343130',
    'bar-alt': '#b5b0ab',
    'on-accent': '#ffffff',
    paper: '#ffffff',
  },
  light: {
    bg: '#f3f2f2',
    surface: '#eceaea',
    card: '#ffffff',
    text: '#201e1d',
    divider: '#d8d5d3',
    track: '#d7d3d3',
    'bar-alt': '#605d5d',
    'on-accent': '#ffffff',
    paper: '#ffffff',
  },
};

export const NEUTRAL_RAMPS: Record<Theme, Ramp> = {
  dark: {
    100: '#2a2725',
    200: '#211f1e',
    300: '#343130',
    400: '#57524e',
    500: '#75706c',
    600: '#948f8b',
    700: '#b5b0ab',
    800: '#d6d2ce',
    900: '#f3f2f2',
  },
  light: {
    100: '#f8f4f4',
    200: '#eae7e7',
    300: '#d7d3d3',
    400: '#bab6b6',
    500: '#9b9797',
    600: '#7d7979',
    700: '#605d5d',
    800: '#444141',
    900: '#2d2b2b',
  },
};

/** `color.accent` itself is theme-independent — design.md §3 marks dark "same". */
export const ACCENT_BASE: Record<Accent, string> = {
  red: '#ec3013',
  blue: '#0f62fe',
  green: '#198038',
};

export const ACCENT_RAMPS: Record<Accent, Record<Theme, Ramp>> = {
  red: {
    dark: {
      100: '#42150e',
      200: '#5e241a',
      300: '#7c3426',
      400: '#9b4433',
      500: '#bb5641',
      600: '#dd674e',
      700: '#ff7a5c',
      800: '#ff9c82',
      900: '#fdbba9',
    },
    light: {
      100: '#fff2ef',
      200: '#ffe0d9',
      300: '#ffc4b8',
      400: '#ff9783',
      500: '#ff563c',
      600: '#dd2b0f',
      700: '#ae1800',
      800: '#7c1405',
      900: '#4d170e',
    },
  },
  blue: {
    dark: {
      100: '#0f244b',
      200: '#1c3669',
      300: '#2a4b8a',
      400: '#375fac',
      500: '#4776ce',
      600: '#558cf3',
      700: '#76a6ff',
      800: '#94baff',
      900: '#b3ceff',
    },
    light: {
      100: '#f0f6ff',
      200: '#dce9ff',
      300: '#bed5ff',
      400: '#90b8ff',
      500: '#5c94ff',
      600: '#226dff',
      700: '#1251cc',
      800: '#0e3a91',
      900: '#102958',
    },
  },
  green: {
    dark: {
      100: '#152c19',
      200: '#244129',
      300: '#335739',
      400: '#436e4a',
      500: '#55875c',
      600: '#65a06e',
      700: '#78ba82',
      800: '#98c89e',
      900: '#b8d7bb',
    },
    light: {
      100: '#f1f7f2',
      200: '#deece0',
      300: '#c2ddc5',
      400: '#94c69b',
      500: '#59ae69',
      600: '#35904a',
      700: '#236f36',
      800: '#1a4f26',
      900: '#17331c',
    },
  },
};

/** `accent-strong` = ramp 700, `accent-tint` = ramp 100 (docs/design.md §3). */
export const ACCENT_STRONG_STEP: RampStep = 700;
export const ACCENT_TINT_STEP: RampStep = 100;

export const accentStrong = (accent: Accent, theme: Theme): string =>
  ACCENT_RAMPS[accent][theme][ACCENT_STRONG_STEP];

export const accentTint = (accent: Accent, theme: Theme): string =>
  ACCENT_RAMPS[accent][theme][ACCENT_TINT_STEP];

/** space.1..8 — docs/design.md §3. */
export const SPACING: Record<number, string> = {
  1: '4px',
  2: '8px',
  3: '12px',
  4: '16px',
  5: '20px',
  6: '22px',
  7: '32px',
  8: '56px',
};

export type TypeToken = {
  name: string;
  /** px at Default text size; scales with --gd-text-scale. */
  size: number;
  lineHeight: string;
  tracking: string;
  usage: string;
};

/** Type scale — docs/design.md §3 "Typography", sizes at Default (14px base). */
export const TYPE_SCALE: TypeToken[] = [
  { name: 'display', size: 52, lineHeight: '1.05', tracking: '-0.045em', usage: 'Session clocks' },
  { name: 'h1', size: 36, lineHeight: '1.1', tracking: '-0.015em', usage: 'Panel titles, big stats' },
  { name: 'h2', size: 23, lineHeight: '1.15', tracking: '-0.015em', usage: 'Screen title' },
  { name: 'h3', size: 18, lineHeight: '1.2', tracking: '-0.015em', usage: 'Card titles' },
  { name: 'body', size: 14, lineHeight: '1.5', tracking: '0em', usage: 'Default' },
  { name: 'label', size: 10, lineHeight: '1.2', tracking: '0.14em', usage: 'Kickers, table headers' },
  { name: 'mono', size: 11, lineHeight: '1.5', tracking: '0em', usage: 'Receipt/stub previews only' },
];

/** Base font size per S13 text-size choice, and the scale factor that drives it. */
export const TEXT_SIZE_SCALE: Record<TextSize, { scale: string; basePx: number }> = {
  compact: { scale: '0.9286', basePx: 13 },
  default: { scale: '1', basePx: 14 },
  large: { scale: '1.1429', basePx: 16 },
};

/** Structural constants — radius 0, 2px rules, 45% disabled. */
export const STRUCTURE = {
  radius: '0px',
  ruleStrong: '2px',
  ruleHair: '1px',
  focusRing: '2px',
  disabledOpacity: '0.45',
} as const;
