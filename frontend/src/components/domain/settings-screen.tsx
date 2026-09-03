'use client';

/**
 * S13 — Settings (design.md §6).
 *
 * The screen is design.md §6's table, in its order: Appearance · Login screen ·
 * Terminal · Profile. Two rules shape all of it.
 *
 * **Instant, then persisted.** Theme, text size and accent are applied from
 * local state the moment a control moves — `applyAppearance` stamps
 * `data-theme` / `data-text-size` / `data-accent` on `<html>` and the whole app
 * repaints from `styles/tokens.css` — and only then does the `PUT` go out. The
 * operator never watches a spinner to find out what Light looks like, and a
 * failed save leaves their choice on screen with a notice rather than snapping
 * the venue back to dark mid-sentence (an error never destroys entered data,
 * §4.4). The saved row is what survives a reload, through the localStorage
 * hint the pre-paint script in `app/layout.tsx` reads (§5.5).
 *
 * **The terminal is the owner's, the swatch is yours.** `PUT
 * /terminal-settings` is Admin-only (api-contract.md, Settings), so a
 * cashier's controls are read-only and say why; `PUT /me/prefs` is every
 * role's, so the profile colour is live for all three. Hiding stays cosmetic —
 * the API answers 403 whatever was drawn.
 */

import { useState } from 'react';
import { Check } from 'lucide-react';
import { AvatarSwatch, AVATAR_COLORS } from '@/components/ui/avatar-swatch';
import { Button } from '@/components/ui/button';
import { ChipSelect } from '@/components/ui/chip-select';
import { ImagePicker } from '@/components/ui/image-picker';
import { SegmentedChoice } from '@/components/ui/segmented-choice';
import { AccessNotice } from './access-notice';
import { initialsOf } from './signed-in-card';
import { errorNotice, isApiError } from '@/lib/api';
import type { Role } from '@/lib/nav';
import { useSession } from '@/features/auth/session';
import { useTerminalSettings } from '@/features/settings/use-terminal-settings';
import { usePrefs } from '@/features/settings/queries';
import {
  useUpdatePrefs,
  useUpdateTerminalSettings,
  useUploadLoginBg,
} from '@/features/settings/mutations';
import { loginBgUrl } from '@/features/settings/login-bg';
import {
  ACCENT_LABELS,
  TEXT_SIZE_LABELS,
  THEME_LABELS,
  applyAppearance,
} from '@/features/settings/appearance';
import {
  AUTO_LOCK_CHOICES,
  RECEIPT_COPY_CHOICES,
  autoLockLabel,
  canEditTerminalSettings,
  draftAppearance,
  settingsDraft,
  type SettingsDraft,
} from '@/features/settings/schemas';
import {
  ACCENTS,
  TEXT_SIZES,
  THEMES,
  type Accent,
  type TextSize,
  type Theme,
} from '@/styles/tokens';

export type SettingsScreenProps = {
  /** The role the middleware just read; the API re-checks every write. */
  role: Role | null;
};

export function SettingsScreen({ role }: SettingsScreenProps) {
  const session = useSession();
  const settings = useTerminalSettings();
  const prefs = usePrefs();
  const save = useUpdateTerminalSettings();
  const upload = useUploadLoginBg();
  const savePrefs = useUpdatePrefs();

  const editable = canEditTerminalSettings(role);

  // The draft is the screen. It starts as the server's row and, once the
  // operator has touched anything, stays theirs — a save that fails must not
  // silently drop the choice they are looking at.
  const [draft, setDraft] = useState<SettingsDraft | null>(null);
  const current = draft ?? settingsDraft(settings.data);

  const [notice, setNotice] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  /** A picture chosen this second, before the settings round-trip returns. */
  const [pickedBg, setPickedBg] = useState<string | null>(null);

  const persist = (next: SettingsDraft) => {
    setNotice(null);
    setSaved(false);
    save.mutate(next, {
      onSuccess: () => setSaved(true),
      onError: (error) =>
        setNotice(
          errorNotice(
            error,
            'This terminal could not be saved. The change is applied here until it reloads.',
          ),
        ),
    });
  };

  /** Apply first, save second — design.md §6's "instantly". */
  const change = (patch: Partial<SettingsDraft>) => {
    const next = { ...current, ...patch };
    setDraft(next);
    applyAppearance(draftAppearance(next));
    persist(next);
  };

  const pickBackground = (value: string | null, file?: File) => {
    setNotice(null);
    setSaved(false);
    if (!file) {
      setPickedBg(null);
      change({ loginBgImageId: null });
      return;
    }
    // The picture is stored first and attached second: the upload answers with
    // the id, and the id is what the terminal's row carries.
    setPickedBg(value);
    upload.mutate(
      { file },
      {
        onSuccess: (uploaded) => change({ loginBgImageId: uploaded.loginBgImageId ?? null }),
        onError: (error) => {
          setPickedBg(null);
          setNotice(errorNotice(error, 'That image could not be uploaded.'));
        },
      },
    );
  };

  const avatarColor = prefs.data?.avatarColor ?? session.staff?.avatarColor ?? null;

  const chooseAvatar = (colour: string | null) => {
    setNotice(null);
    setSaved(false);
    savePrefs.mutate(
      { avatarColor: colour },
      {
        // The sidebar avatar is the session's, not the query's: move both, so
        // the swatch and the corner agree without a sign-out.
        onSuccess: (next) => session.setAvatarColor(next.avatarColor ?? null),
        onError: (error) => setNotice(errorNotice(error, 'That colour could not be saved.')),
      },
    );
  };

  const backgroundValue =
    pickedBg ?? (current.loginBgImageId ? loginBgUrl(current.loginBgImageId) : null);

  // S13 is open to every role, so this is the disagreement case: a terminal
  // whose row the API refuses outright has nothing to show behind the form
  // (design.md §1 — an API 403 renders as an access notice).
  if (isApiError(settings.error) && settings.error.status === 403) {
    return <AccessNotice screen="Settings" />;
  }

  return (
    <section data-testid="settings-screen" className="flex flex-col gap-5 p-8">
      <header className="flex flex-col gap-1">
        <p className="type-label text-accent-strong">S13</p>
        <h1 className="text-h2">Settings</h1>
        <p className="text-body opacity-70">
          Appearance and behaviour for this terminal, and the colour that marks your name.
        </p>
      </header>

      {notice ? (
        <p
          role="alert"
          data-testid="settings-notice"
          className="max-w-[720px] border-2 border-accent px-3 py-2 text-body text-accent-strong"
        >
          {notice}
        </p>
      ) : null}

      {saved ? (
        <p
          role="status"
          data-testid="settings-saved"
          className="flex max-w-[720px] items-center gap-2 border-2 border-divider px-3 py-2 text-body"
        >
          <Check aria-hidden="true" className="size-4" strokeWidth={2} />
          Saved for this terminal.
        </p>
      ) : null}

      {!editable ? (
        <p data-testid="settings-readonly" className="max-w-[720px] text-[12px] opacity-60">
          The terminal&rsquo;s appearance, sound, auto-lock and receipt copies are set by the
          owner. Your profile colour below is yours to change.
        </p>
      ) : null}

      {settings.isPending ? (
        <div
          data-testid="settings-skeleton"
          aria-busy="true"
          className="flex max-w-[720px] flex-col gap-4"
        >
          {[0, 1, 2].map((group) => (
            <div key={group} className="flex flex-col gap-2 border-2 border-divider p-4">
              <div className="h-3 w-32 bg-track" />
              <div className="h-9 w-64 bg-track" />
              <div className="h-9 w-48 bg-track" />
            </div>
          ))}
        </div>
      ) : settings.isError ? (
        <p
          role="alert"
          data-testid="settings-error"
          className="max-w-[720px] text-body text-accent-strong"
        >
          {errorNotice(settings.error, 'This terminal’s settings could not be read.')}
        </p>
      ) : (
        <div className="flex max-w-[720px] flex-col gap-4">
          <Group title="Appearance">
            <Field label="Theme" hint="Dark is the default — the venue is dim.">
              <SegmentedChoice<Theme>
                label="Theme"
                value={current.theme}
                onChange={(theme) => change({ theme })}
                options={THEMES.map((theme) => ({
                  value: theme,
                  label: THEME_LABELS[theme],
                  disabled: !editable,
                }))}
              />
            </Field>

            <Field label="Text size" hint="Scales the whole type ramp, clocks included.">
              <SegmentedChoice<TextSize>
                label="Text size"
                value={current.textSize}
                onChange={(textSize) => change({ textSize })}
                options={TEXT_SIZES.map((size) => ({
                  value: size,
                  label: TEXT_SIZE_LABELS[size],
                  disabled: !editable,
                }))}
              />
            </Field>

            <Field label="Accent colour" hint="Swaps the full tonal ramp in both themes.">
              <ChipSelect<Accent>
                label="Accent colour"
                value={current.accent}
                onChange={(accent) => change({ accent })}
                options={ACCENTS.map((accent) => ({
                  value: accent,
                  label: ACCENT_LABELS[accent],
                  disabled: !editable,
                }))}
              />
            </Field>
          </Group>

          <Group title="Login screen">
            <ImagePicker
              label="Background image"
              value={backgroundValue}
              onChange={pickBackground}
              disabled={!editable || upload.isPending}
              previewLabel="Shown under a dark overlay"
              emptyLabel="No background — the brand panel stands alone"
            />
          </Group>

          <Group title="Terminal">
            <Field label="Alert & time-up sound" hint="Chimes when a session runs out.">
              <SegmentedChoice<'on' | 'off'>
                label="Alert and time-up sound"
                value={current.sound ? 'on' : 'off'}
                onChange={(value) => change({ sound: value === 'on' })}
                options={[
                  { value: 'on', label: 'On', disabled: !editable },
                  { value: 'off', label: 'Off', disabled: !editable },
                ]}
              />
            </Field>

            <Field label="Auto-lock" hint="Idle minutes before the terminal asks for a PIN.">
              <SegmentedChoice<string>
                label="Auto-lock"
                value={String(current.autoLockMin)}
                onChange={(value) => change({ autoLockMin: Number(value) })}
                options={AUTO_LOCK_CHOICES.map((minutes) => ({
                  value: String(minutes),
                  label: autoLockLabel(minutes),
                  disabled: !editable,
                }))}
              />
            </Field>

            <Field label="Receipt copies" hint="How many tickets every sale prints.">
              <SegmentedChoice<string>
                label="Receipt copies"
                value={String(current.receiptCopies)}
                onChange={(value) => change({ receiptCopies: Number(value) })}
                options={RECEIPT_COPY_CHOICES.map((copies) => ({
                  value: String(copies),
                  label: String(copies),
                  disabled: !editable,
                }))}
              />
            </Field>
          </Group>

          <Group title="Profile">
            <Field
              label="Avatar colour"
              hint="Yours, not the terminal’s — it follows you to any counter."
            >
              <div data-testid="avatar-palette" className="flex items-center gap-2">
                {AVATAR_COLORS.map((colour) => (
                  <AvatarSwatch
                    key={colour}
                    color={colour}
                    label={`Avatar colour ${colour}`}
                    selected={avatarColor === colour}
                    onSelect={() => chooseAvatar(colour)}
                  />
                ))}
                <Button
                  variant="ghost"
                  size="sm"
                  disabled={avatarColor === null || savePrefs.isPending}
                  onClick={() => chooseAvatar(null)}
                >
                  Reset
                </Button>
              </div>
            </Field>

            <div className="flex items-center gap-2.5">
              <AvatarSwatch
                color={avatarColor}
                initials={initialsOf(session.staff?.name ?? '')}
                size="lg"
              />
              <p className="text-[12px] opacity-60">
                How your name is marked on the sidebar, shift records and reprints.
              </p>
            </div>
          </Group>
        </div>
      )}
    </section>
  );
}

function Group({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="flex flex-col gap-3.5 border-2 border-text p-4">
      <h2 className="type-label opacity-55">{title}</h2>
      {children}
    </section>
  );
}

function Field({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <div className="flex flex-col gap-1.5">
      <p className="font-heading text-[13px] font-extrabold">{label}</p>
      {children}
      {hint ? <p className="text-[12px] opacity-60">{hint}</p> : null}
    </div>
  );
}
