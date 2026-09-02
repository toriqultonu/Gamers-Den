import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import TokensPage from '@/app/tokens/page';
import {
  ACCENT_BASE,
  ACCENT_RAMPS,
  ACCENTS,
  DEFAULT_ACCENT,
  DEFAULT_TEXT_SIZE,
  DEFAULT_THEME,
  NEUTRAL_RAMPS,
  RAMP_STEPS,
  SPACING,
  STRUCTURE,
  SURFACE_TOKENS,
  TEXT_SIZE_SCALE,
  TEXT_SIZES,
  THEMES,
  TYPE_SCALE,
  accentStrong,
  accentTint,
} from '@/styles/tokens';

/* ---------------------------------------------------------------------------
 * A tiny CSS reader. jsdom does not cascade custom properties across
 * stylesheets, so instead of asking getComputedStyle we read the declarations
 * out of tokens.css directly: selector -> { property: value }.
 * ------------------------------------------------------------------------ */

const read = (rel: string) => readFileSync(resolve(process.cwd(), rel), 'utf8');

const CSS = read('src/styles/tokens.css').replace(/\/\*[\s\S]*?\*\//g, '');

function declarations(): Map<string, Map<string, string>> {
  const blocks = new Map<string, Map<string, string>>();
  for (const [, rawSelectors, body] of CSS.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
    const decls = new Map<string, string>();
    for (const line of body.split(';')) {
      const at = line.indexOf(':');
      if (at === -1) continue;
      decls.set(line.slice(0, at).trim(), line.slice(at + 1).trim());
    }
    for (const selector of rawSelectors.split(',')) {
      const key = selector.trim().replace(/\s+/g, ' ');
      if (!key) continue;
      const existing = blocks.get(key);
      if (existing) decls.forEach((v, k) => existing.set(k, v));
      else blocks.set(key, new Map(decls));
    }
  }
  return blocks;
}

const BLOCKS = declarations();

const cssVar = (selector: string, property: string): string | undefined =>
  BLOCKS.get(selector)?.get(property);

const themeSel = (theme: string) => `[data-theme='${theme}']`;
const accentSel = (theme: string, accent: string) =>
  `[data-theme='${theme}'][data-accent='${accent}']`;

/* ------------------------------------------------------------------------ */

describe('tokens.css — docs/design.md §3', () => {
  it('pins the documented ground, surface and ink values for both themes', () => {
    // Dark is the default theme (dim venue).
    expect(cssVar(themeSel('dark'), '--gd-bg')).toBe('#171514');
    expect(cssVar(themeSel('dark'), '--gd-surface')).toBe('#211f1e');
    expect(cssVar(themeSel('dark'), '--gd-card')).toBe('#2a2725');
    expect(cssVar(themeSel('dark'), '--gd-text')).toBe('#f3f2f2');
    expect(cssVar(themeSel('dark'), '--gd-divider')).toBe('#3c3835');
    expect(cssVar(themeSel('dark'), '--gd-track')).toBe('#343130');
    expect(cssVar(themeSel('dark'), '--gd-bar-alt')).toBe('#b5b0ab');

    expect(cssVar(themeSel('light'), '--gd-bg')).toBe('#f3f2f2');
    expect(cssVar(themeSel('light'), '--gd-surface')).toBe('#eceaea');
    expect(cssVar(themeSel('light'), '--gd-card')).toBe('#ffffff');
    expect(cssVar(themeSel('light'), '--gd-text')).toBe('#201e1d');
    expect(cssVar(themeSel('light'), '--gd-divider')).toBe('#d8d5d3');
    expect(cssVar(themeSel('light'), '--gd-track')).toBe('#d7d3d3');
    expect(cssVar(themeSel('light'), '--gd-bar-alt')).toBe('#605d5d');

    // on-accent and paper are fixed in both themes.
    for (const theme of THEMES) {
      expect(cssVar(themeSel(theme), '--gd-on-accent')).toBe('#ffffff');
      expect(cssVar(themeSel(theme), '--gd-paper')).toBe('#ffffff');
    }
  });

  it('pins the three selectable accents and the anchors quoted in design.md', () => {
    for (const theme of THEMES) {
      expect(cssVar(accentSel(theme, 'red'), '--gd-accent')).toBe('#ec3013');
      expect(cssVar(accentSel(theme, 'blue'), '--gd-accent')).toBe('#0f62fe');
      expect(cssVar(accentSel(theme, 'green'), '--gd-accent')).toBe('#198038');
    }

    // "brightened equivalent (e.g. #ff7a5c)" / "dark equivalent (e.g. #42150e)"
    expect(cssVar(accentSel('dark', 'red'), '--gd-accent-700')).toBe('#ff7a5c');
    expect(cssVar(accentSel('dark', 'red'), '--gd-accent-100')).toBe('#42150e');
    expect(cssVar(accentSel('dark', 'red'), '--gd-accent-800')).toBe('#ff9c82');
    // Ramp 700 of the light Modernist palette.
    expect(cssVar(accentSel('light', 'red'), '--gd-accent-700')).toBe('#ae1800');
  });

  it('overrides the full 100–900 ramp for every theme × accent', () => {
    for (const theme of THEMES) {
      for (const step of RAMP_STEPS) {
        expect(cssVar(themeSel(theme), `--gd-neutral-${step}`)).toBe(
          NEUTRAL_RAMPS[theme][step],
        );
      }
      for (const accent of ACCENTS) {
        for (const step of RAMP_STEPS) {
          expect(cssVar(accentSel(theme, accent), `--gd-accent-${step}`)).toBe(
            ACCENT_RAMPS[accent][theme][step],
          );
        }
      }
    }
  });

  it('derives accent-strong from ramp 700 and accent-tint from ramp 100', () => {
    for (const theme of THEMES) {
      for (const accent of ACCENTS) {
        expect(cssVar(accentSel(theme, accent), '--gd-accent-strong')).toBe(
          'var(--gd-accent-700)',
        );
        expect(cssVar(accentSel(theme, accent), '--gd-accent-tint')).toBe(
          'var(--gd-accent-100)',
        );
      }
    }
  });

  it('exposes the spacing scale, radius 0 and the 2px/1px rules', () => {
    for (const [step, value] of Object.entries(SPACING)) {
      expect(cssVar(':root', `--gd-space-${step}`)).toBe(value);
    }
    expect(cssVar(':root', '--gd-radius')).toBe(STRUCTURE.radius);
    expect(cssVar(':root', '--gd-rule-strong')).toBe(STRUCTURE.ruleStrong);
    expect(cssVar(':root', '--gd-rule-hair')).toBe(STRUCTURE.ruleHair);
    expect(cssVar(':root', '--gd-focus-ring')).toBe(STRUCTURE.focusRing);
    expect(cssVar(':root', '--gd-disabled-opacity')).toBe(STRUCTURE.disabledOpacity);
  });

  it('exposes the Archivo type scale, each size scaled by the text-size choice', () => {
    expect(cssVar(':root', '--gd-font-body')).toContain('Archivo');
    expect(cssVar(':root', '--gd-font-heading')).toContain('Archivo');
    expect(cssVar(':root', '--gd-font-weight-heading')).toBe('800');

    for (const token of TYPE_SCALE) {
      expect(cssVar(':root', `--gd-text-${token.name}`)).toBe(
        `calc(${token.size}px * var(--gd-text-scale))`,
      );
      expect(cssVar(':root', `--gd-leading-${token.name}`)).toBe(token.lineHeight);
      expect(cssVar(':root', `--gd-tracking-${token.name}`)).toBe(token.tracking);
    }

    for (const size of TEXT_SIZES) {
      expect(cssVar(`[data-text-size='${size}']`, '--gd-text-scale')).toBe(
        TEXT_SIZE_SCALE[size].scale,
      );
    }
  });
});

describe('globals.css — Tailwind @theme mapping', () => {
  const GLOBALS = read('src/app/globals.css');

  it('maps every semantic colour role onto a Tailwind colour token', () => {
    for (const role of [
      'bg',
      'surface',
      'card',
      'text',
      'divider',
      'track',
      'bar-alt',
      'on-accent',
      'paper',
      'accent',
      'accent-strong',
      'accent-tint',
    ]) {
      expect(GLOBALS).toContain(`--color-${role}: var(--gd-${role});`);
    }
    for (const step of RAMP_STEPS) {
      expect(GLOBALS).toContain(`--color-accent-${step}: var(--gd-accent-${step});`);
      expect(GLOBALS).toContain(`--color-neutral-${step}: var(--gd-neutral-${step});`);
    }
  });

  it('maps the spacing scale and forces radius 0 everywhere', () => {
    for (const step of Object.keys(SPACING)) {
      expect(GLOBALS).toContain(`--spacing-${step}: var(--gd-space-${step});`);
    }
    for (const name of ['none', 'xs', 'sm', 'md', 'lg', 'xl', '2xl', '3xl', '4xl']) {
      expect(GLOBALS).toContain(`--radius-${name}: var(--gd-radius);`);
    }
  });

  it('ships the tabular-nums utility bills, clocks and tables depend on', () => {
    expect(GLOBALS).toMatch(/@utility tabular \{\s*font-variant-numeric: tabular-nums;/);
  });
});

describe('/tokens demo page', () => {
  it('renders all six theme × accent combinations', () => {
    render(<TokensPage />);

    const matrix = screen.getByTestId('theme-accent-matrix');
    expect(matrix.children).toHaveLength(THEMES.length * ACCENTS.length);

    for (const theme of THEMES) {
      for (const accent of ACCENTS) {
        const panel = screen.getByTestId(`token-panel-${theme}-${accent}`);
        expect(panel).toHaveAttribute('data-theme', theme);
        expect(panel).toHaveAttribute('data-accent', accent);
      }
    }
  });

  it('prints the correct value for every token in every combination', () => {
    render(<TokensPage />);

    for (const theme of THEMES) {
      for (const accent of ACCENTS) {
        const panel = within(screen.getByTestId(`token-panel-${theme}-${accent}`));

        for (const [role, value] of Object.entries(SURFACE_TOKENS[theme])) {
          expect(panel.getByTestId(`swatch-${theme}-${accent}-color.${role}`)).toHaveAttribute(
            'data-value',
            value,
          );
        }

        expect(panel.getByTestId(`swatch-${theme}-${accent}-color.accent`)).toHaveAttribute(
          'data-value',
          ACCENT_BASE[accent],
        );
        expect(
          panel.getByTestId(`swatch-${theme}-${accent}-color.accent-strong`),
        ).toHaveAttribute('data-value', accentStrong(accent, theme));
        expect(panel.getByTestId(`swatch-${theme}-${accent}-color.accent-tint`)).toHaveAttribute(
          'data-value',
          accentTint(accent, theme),
        );

        for (const step of RAMP_STEPS) {
          expect(panel.getByTestId(`ramp-${theme}-${accent}-accent-${step}`)).toHaveAttribute(
            'data-value',
            ACCENT_RAMPS[accent][theme][step],
          );
          expect(panel.getByTestId(`ramp-${theme}-${accent}-neutral-${step}`)).toHaveAttribute(
            'data-value',
            NEUTRAL_RAMPS[theme][step],
          );
        }
      }
    }
  });

  it('renders the spacing, type and text-size specimens', () => {
    render(<TokensPage />);

    for (const [step, value] of Object.entries(SPACING)) {
      expect(screen.getByTestId(`space-${step}`)).toHaveAttribute('data-value', value);
    }
    for (const token of TYPE_SCALE) {
      expect(screen.getByTestId(`type-${token.name}`)).toHaveAttribute(
        'data-value',
        `${token.size}px`,
      );
    }
    for (const size of TEXT_SIZES) {
      const el = screen.getByTestId(`text-size-${size}`);
      expect(el).toHaveAttribute('data-text-size', size);
      expect(el).toHaveAttribute('data-value', `${TEXT_SIZE_SCALE[size].basePx}px`);
    }
  });
});

describe('defaults', () => {
  it('boots dark + Den Red at the Default text size', () => {
    expect(DEFAULT_THEME).toBe('dark');
    expect(DEFAULT_ACCENT).toBe('red');
    expect(DEFAULT_TEXT_SIZE).toBe('default');
    // An html element that lost its attributes still paints the dark ramp.
    expect(cssVar(':root:not([data-theme])', '--gd-bg')).toBe('#171514');
    expect(cssVar(':root:not([data-accent])', '--gd-accent')).toBe('#ec3013');
  });
});
