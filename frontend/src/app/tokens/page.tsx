import type { Metadata } from 'next';
import {
  ACCENT_BASE,
  ACCENT_LABELS,
  ACCENT_RAMPS,
  ACCENTS,
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
  type Accent,
  type Theme,
} from '@/styles/tokens';

export const metadata: Metadata = {
  title: "Design tokens — Gamer's Den",
};

/**
 * S0 (not a product screen) — the token reference.
 *
 * Renders every docs/design.md §3 token in both themes across all three
 * accents. Each panel carries its own `data-theme` / `data-accent`, so the
 * colour you see is the CSS cascade resolving `src/styles/tokens.css` for that
 * combination — not a hard-coded fill. The printed hex next to each chip comes
 * from `src/styles/tokens.ts`, which `tests/tokens.test.ts` pins against the
 * stylesheet.
 */

type SwatchProps = {
  token: string;
  cssVar: string;
  value: string;
  /** Draw a border when the chip can vanish into the panel ground. */
  outlined?: boolean;
};

function Swatch({ token, cssVar, value, outlined }: SwatchProps) {
  return (
    <div
      data-testid={`swatch-${token}`}
      data-token={token}
      data-value={value}
      className="flex items-center gap-2"
    >
      <span
        aria-hidden
        className="block size-7 shrink-0 border-hair"
        style={{ backgroundColor: `var(${cssVar})` }}
      />
      <span className="min-w-0">
        <span className="type-label block opacity-70">{token}</span>
        <span className="tabular block text-body">{value}</span>
      </span>
    </div>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="flex flex-col gap-3">
      <h3 className="type-label text-accent-strong">{title}</h3>
      {children}
    </section>
  );
}

function TokenPanel({ theme, accent }: { theme: Theme; accent: Accent }) {
  const surfaces = SURFACE_TOKENS[theme];
  const accentRamp = ACCENT_RAMPS[accent][theme];
  const neutralRamp = NEUTRAL_RAMPS[theme];

  return (
    <article
      data-theme={theme}
      data-accent={accent}
      data-testid={`token-panel-${theme}-${accent}`}
      className="flex flex-col gap-5 border-rule bg-bg p-5 text-text"
    >
      <header className="flex items-baseline justify-between gap-3 border-b-2 border-divider pb-3">
        <h2 className="text-h2">
          {theme === 'dark' ? 'Dark' : 'Light'} · {ACCENT_LABELS[accent]}
        </h2>
        <span className="type-label opacity-60">
          data-theme=&quot;{theme}&quot; data-accent=&quot;{accent}&quot;
        </span>
      </header>

      <Section title="Semantic roles">
        <div className="grid grid-cols-3 gap-3">
          {Object.entries(surfaces).map(([name, value]) => (
            <Swatch
              key={name}
              token={`${theme}-${accent}-color.${name}`}
              cssVar={`--gd-${name}`}
              value={value}
              outlined
            />
          ))}
          <Swatch
            token={`${theme}-${accent}-color.accent`}
            cssVar="--gd-accent"
            value={ACCENT_BASE[accent]}
          />
          <Swatch
            token={`${theme}-${accent}-color.accent-strong`}
            cssVar="--gd-accent-strong"
            value={accentStrong(accent, theme)}
          />
          <Swatch
            token={`${theme}-${accent}-color.accent-tint`}
            cssVar="--gd-accent-tint"
            value={accentTint(accent, theme)}
          />
        </div>
      </Section>

      <Section title={`Accent ramp — ${ACCENT_LABELS[accent]}`}>
        <div className="grid grid-cols-9 border-hair">
          {RAMP_STEPS.map((step) => (
            <div
              key={step}
              data-testid={`ramp-${theme}-${accent}-accent-${step}`}
              data-token={`${theme}-${accent}-color.accent-${step}`}
              data-value={accentRamp[step]}
              className="flex flex-col"
            >
              <span
                aria-hidden
                className="block h-10"
                style={{ backgroundColor: `var(--gd-accent-${step})` }}
              />
              <span className="type-label p-1 opacity-70">{step}</span>
              <span className="tabular px-1 pb-1 text-[10px]">{accentRamp[step]}</span>
            </div>
          ))}
        </div>
      </Section>

      <Section title="Neutral ramp">
        <div className="grid grid-cols-9 border-hair">
          {RAMP_STEPS.map((step) => (
            <div
              key={step}
              data-testid={`ramp-${theme}-${accent}-neutral-${step}`}
              data-token={`${theme}-${accent}-color.neutral-${step}`}
              data-value={neutralRamp[step]}
              className="flex flex-col"
            >
              <span
                aria-hidden
                className="block h-10"
                style={{ backgroundColor: `var(--gd-neutral-${step})` }}
              />
              <span className="type-label p-1 opacity-70">{step}</span>
              <span className="tabular px-1 pb-1 text-[10px]">{neutralRamp[step]}</span>
            </div>
          ))}
        </div>
      </Section>

      <Section title="In context">
        <div className="flex flex-wrap items-center gap-3">
          <span className="bg-accent px-3 py-2 font-heading text-body font-[800] text-on-accent">
            Primary action
          </span>
          <span className="border-rule border-accent bg-accent-tint px-3 py-2 text-body text-accent-strong">
            Locked station
          </span>
          <span className="bg-card px-3 py-2 text-body">Card surface</span>
          <span className="bg-surface px-3 py-2 text-body">Rail surface</span>
          <span className="tabular text-display">01:30</span>
        </div>
        <div className="flex items-center gap-3">
          <span className="type-label w-24 opacity-60">Track / alt</span>
          <span className="h-3 w-40 bg-track" aria-hidden />
          <span className="h-3 w-24 bg-bar-alt" aria-hidden />
        </div>
        <div className="type-mono bg-paper p-3 text-[#000]">
          GAMER&apos;S DEN{'\n'}TOKEN #14 · 2 H PREPAID
        </div>
      </Section>
    </article>
  );
}

export default function TokensPage() {
  return (
    <main className="flex flex-col gap-8 bg-bg p-8 text-text">
      <header className="flex flex-col gap-2">
        <p className="type-label text-accent-strong">Design system</p>
        <h1 className="text-h1">Tokens</h1>
        <p className="max-w-[70ch] text-body opacity-75">
          Every token in docs/design.md §3, rendered through the real cascade in both themes across
          all three accents. Dark is the default; theme, text size and accent are per-terminal
          settings (S13).
        </p>
      </header>

      <hr className="rule" />

      <div
        data-testid="theme-accent-matrix"
        className="grid grid-cols-1 gap-6 xl:grid-cols-2"
      >
        {THEMES.map((theme) =>
          ACCENTS.map((accent) => (
            <TokenPanel key={`${theme}-${accent}`} theme={theme} accent={accent} />
          )),
        )}
      </div>

      <hr className="rule" />

      <section className="flex flex-col gap-4">
        <h2 className="text-h2">Type scale</h2>
        <table className="w-full border-collapse text-left">
          <thead>
            <tr className="border-b-2 border-divider">
              <th className="type-label py-2 opacity-60">Token</th>
              <th className="type-label py-2 opacity-60">Size / line</th>
              <th className="type-label py-2 opacity-60">Tracking</th>
              <th className="type-label py-2 opacity-60">Usage</th>
            </tr>
          </thead>
          <tbody>
            {TYPE_SCALE.map((t) => (
              <tr
                key={t.name}
                data-testid={`type-${t.name}`}
                data-value={`${t.size}px`}
                className="border-b border-divider"
              >
                <td className="py-2 font-heading text-body">type.{t.name}</td>
                <td className="tabular py-2 text-body">
                  {t.size}/{t.lineHeight}
                </td>
                <td className="tabular py-2 text-body">{t.tracking}</td>
                <td className="py-2 text-body opacity-75">{t.usage}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="flex flex-col gap-2 border-rule p-4">
          <span className="tabular text-display">02:00</span>
          <span className="text-h1">Panel title</span>
          <span className="text-h2">Screen title</span>
          <span className="text-h3">Card title</span>
          <span className="text-body">Body copy at the terminal&apos;s base size.</span>
          <span className="type-label opacity-70">Kicker / table header</span>
          <span className="type-mono">RECEIPT PREVIEW 48 COLS</span>
        </div>
      </section>

      <section className="flex flex-col gap-4">
        <h2 className="text-h2">Text size (S13, per terminal)</h2>
        <div className="flex flex-wrap gap-4">
          {TEXT_SIZES.map((size) => (
            <div
              key={size}
              data-text-size={size}
              data-testid={`text-size-${size}`}
              data-value={`${TEXT_SIZE_SCALE[size].basePx}px`}
              className="flex flex-col gap-1 border-rule p-4"
            >
              <span className="type-label opacity-60">{size}</span>
              <span className="text-body">Body — {TEXT_SIZE_SCALE[size].basePx}px base</span>
              <span className="tabular text-h1">৳1,240</span>
            </div>
          ))}
        </div>
      </section>

      <section className="flex flex-col gap-4">
        <h2 className="text-h2">Spacing</h2>
        <div className="flex flex-col gap-2">
          {Object.entries(SPACING).map(([step, value]) => (
            <div
              key={step}
              data-testid={`space-${step}`}
              data-value={value}
              className="flex items-center gap-3"
            >
              <span className="type-label w-16 opacity-60">space.{step}</span>
              <span className="block h-3 bg-accent" style={{ width: value }} aria-hidden />
              <span className="tabular text-body">{value}</span>
            </div>
          ))}
        </div>
      </section>

      <section className="flex flex-col gap-4">
        <h2 className="text-h2">Structure</h2>
        <dl className="grid grid-cols-2 gap-3 md:grid-cols-5">
          {Object.entries(STRUCTURE).map(([name, value]) => (
            <div key={name} data-testid={`structure-${name}`} data-value={value}>
              <dt className="type-label opacity-60">{name}</dt>
              <dd className="tabular m-0 text-body">{value}</dd>
            </div>
          ))}
        </dl>
        <div className="flex flex-col gap-2">
          <span className="type-label opacity-60">2px rule</span>
          <hr className="rule" />
          <span className="type-label opacity-60">1px hairline</span>
          <hr className="rule-hair" />
        </div>
      </section>
    </main>
  );
}
