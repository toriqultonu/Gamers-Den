'use client';

/**
 * S1 — sign in (design.md §1).
 *
 * Left: the brand statement, over the owner's photograph under a dark overlay
 * when one is set. Right: pick who you are, type the PIN, in.
 *
 * Two things it refuses to do. It never says whether a staff id exists — the
 * server answers 401 for both a wrong id and a wrong PIN, and this screen
 * repeats that answer verbatim. And it never clears the identity on a failure:
 * the PIN is dropped so the next attempt is a fresh one, everything else the
 * operator chose stays (frontend/ARCHITECTURE.md §4.4).
 *
 * The five-try lockout is the server's count, not ours: the envelope carries
 * `attemptsRemaining` on the way down and `lockedUntil` when it runs out.
 */

import { useEffect, useMemo, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import type { Route } from 'next';
import { Lock } from 'lucide-react';
import { AvatarSwatch, Button, FieldInput, cn } from '@/components/ui';
import { PinPad, PIN_LENGTH } from './pin-pad';
import { initialsOf } from './signed-in-card';
import { useSession } from '@/features/auth/session';
import { PIN_IDLE, pinAttemptFrom, type PinAttempt } from '@/features/auth/pin-errors';
import { readRoster, type RosterEntry } from '@/features/auth/staff-roster';
import { loginBgUrl, readCachedLoginBgId } from '@/features/settings/login-bg';
import { ROLE_LABELS, ROLE_NOTES, landingPath } from '@/lib/nav';
import { terminalLabel } from '@/lib/terminal';

export function LoginScreen() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const session = useSession();

  const [roster, setRoster] = useState<RosterEntry[]>([]);
  const [backgroundId, setBackgroundId] = useState<string | null>(null);
  const [staffId, setStaffId] = useState<number | null>(null);
  const [manual, setManual] = useState(false);
  const [manualId, setManualId] = useState('');
  const [pin, setPin] = useState('');
  const [attempt, setAttempt] = useState<PinAttempt>(PIN_IDLE);
  const [busy, setBusy] = useState(false);

  // localStorage is a client-only fact — reading it during render would make
  // the server HTML and the first paint disagree.
  useEffect(() => {
    const known = readRoster();
    setRoster(known);
    setStaffId((current) => current ?? known[0]?.id ?? null);
    setManual(known.length === 0);
    setBackgroundId(readCachedLoginBgId());
  }, []);

  const chosenId = manual ? Number.parseInt(manualId, 10) : staffId;
  const validId = typeof chosenId === 'number' && Number.isInteger(chosenId) && chosenId > 0;
  const locked = attempt.kind === 'locked';
  const canSubmit = validId && pin.length === PIN_LENGTH && !busy && !locked;

  const selected = useMemo(
    () => roster.find((entry) => entry.id === staffId) ?? null,
    [roster, staffId],
  );

  const submit = async () => {
    if (!canSubmit || !validId) return;
    setBusy(true);
    setAttempt(PIN_IDLE);
    try {
      const staff = await session.signIn({ staffId: chosenId, pin });
      // Only a same-origin path is honoured — a `?next=` is attacker-supplied
      // and an absolute URL there is an open redirect.
      const next = searchParams?.get('next');
      const target =
        next && next.startsWith('/') && !next.startsWith('//')
          ? (next as Route)
          : landingPath(staff.role);
      router.replace(target);
    } catch (error) {
      setAttempt(pinAttemptFrom(error));
      setPin('');
    } finally {
      setBusy(false);
    }
  };

  const pick = (entry: RosterEntry) => {
    setStaffId(entry.id);
    setManual(false);
    setAttempt(PIN_IDLE);
    setPin('');
  };

  return (
    <div className="flex min-h-screen bg-bg text-text max-md:flex-col">
      <section
        className={cn(
          'relative flex flex-1 flex-col justify-between overflow-hidden p-14 max-md:p-8',
          'bg-accent text-on-accent',
        )}
        style={
          backgroundId
            ? {
                // design.md §7: the photo always sits under a dark overlay so
                // the type stays readable whatever the owner uploaded.
                backgroundImage: `linear-gradient(rgba(15,10,8,.55), rgba(15,10,8,.75)), url(${loginBgUrl(backgroundId)})`,
                backgroundSize: 'cover',
                backgroundPosition: 'center',
              }
            : undefined
        }
      >
        <div className="flex items-center gap-3.5">
          <span
            aria-hidden="true"
            className="grid size-[62px] flex-none place-items-center bg-white font-heading text-[30px] font-extrabold tracking-[-0.06em] text-accent"
          >
            GD
          </span>
          <span>
            <span className="block font-heading text-[27px] leading-none font-extrabold tracking-[-0.03em]">
              GAMER&rsquo;S DEN
            </span>
            <span className="block text-[11px] tracking-[0.22em] uppercase opacity-85">
              Bogura · Est. 2025
            </span>
          </span>
        </div>

        <div className="max-md:hidden">
          <p className="font-heading text-[62px] leading-[0.98] font-extrabold tracking-[-0.045em] text-balance">
            Every minute
            <br />
            accounted for.
          </p>
          <hr className="my-6 h-0.5 border-0 bg-white/50" />
          <dl className="grid grid-cols-3 gap-5 text-body">
            <div>
              <dt className="font-heading text-[26px] font-extrabold">30 min</dt>
              <dd>billing blocks</dd>
            </div>
            <div>
              <dt className="font-heading text-[26px] font-extrabold">Prepaid</dt>
              <dd>bookings &amp; tokens</dd>
            </div>
            <div>
              <dt className="font-heading text-[26px] font-extrabold">Offline</dt>
              <dd>safe till sync</dd>
            </div>
          </dl>
        </div>
      </section>

      <section className="flex w-full max-w-[480px] flex-none flex-col justify-center gap-4 p-14 max-md:max-w-none max-md:p-8">
        <div>
          <p className="type-label text-accent-strong">{terminalLabel(session.terminal)}</p>
          <h1 className="font-heading text-[36px] font-extrabold tracking-[-0.035em]">Sign in</h1>
        </div>
        <hr className="rule" />

        {locked ? (
          <p
            role="alert"
            data-testid="lockout-notice"
            className="flex items-start gap-2 border-2 border-accent bg-accent-tint p-3 text-body text-accent-800"
          >
            <Lock aria-hidden="true" className="mt-0.5 size-4 shrink-0" strokeWidth={2} />
            {attempt.message}
          </p>
        ) : null}

        <p className="type-label opacity-55">Choose your identity</p>

        {roster.length > 0 ? (
          <div className="flex flex-col gap-2" role="radiogroup" aria-label="Staff">
            {roster.map((entry) => {
              const active = !manual && entry.id === staffId;
              return (
                <button
                  key={entry.id}
                  type="button"
                  role="radio"
                  aria-checked={active}
                  data-testid={`staff-option-${entry.id}`}
                  disabled={busy || locked}
                  onClick={() => pick(entry)}
                  className={cn(
                    'flex items-center gap-3 border-2 p-3 text-left',
                    'focus-visible:outline-2 focus-visible:outline-accent focus-visible:outline-offset-2',
                    'disabled:cursor-not-allowed disabled:opacity-45',
                    active ? 'border-accent bg-accent-tint' : 'border-divider hover:bg-neutral-100',
                  )}
                >
                  <AvatarSwatch color={entry.avatarColor} initials={initialsOf(entry.name)} />
                  <span className="min-w-0">
                    <span className="block truncate font-heading text-h3">{entry.name}</span>
                    <span className="block truncate text-[12px] opacity-65">
                      {ROLE_NOTES[entry.role]}
                    </span>
                  </span>
                  <span className="type-label ml-auto shrink-0 opacity-55">
                    {ROLE_LABELS[entry.role]}
                  </span>
                </button>
              );
            })}
            <Button
              variant="ghost"
              disabled={busy || locked}
              onClick={() => {
                setManual(true);
                setAttempt(PIN_IDLE);
              }}
            >
              Someone else — sign in by staff ID
            </Button>
          </div>
        ) : null}

        {manual ? (
          <FieldInput
            label="Staff ID"
            inputMode="numeric"
            autoComplete="off"
            value={manualId}
            disabled={busy || locked}
            onChange={(event) => setManualId(event.target.value.replace(/\D/g, '').slice(0, 9))}
            hint={
              roster.length === 0
                ? 'This terminal has no saved staff yet — sign in once and the picker remembers you.'
                : undefined
            }
          />
        ) : null}

        <PinPad
          value={pin}
          onChange={setPin}
          onSubmit={submit}
          disabled={busy || locked}
          error={attempt.kind === 'wrong' || attempt.kind === 'error' ? attempt.message : undefined}
        />

        <Button variant="block" size="lg" loading={busy} disabled={!canSubmit} onClick={submit}>
          Sign in
        </Button>

        <p className="text-[12px] opacity-55">
          {selected && !manual
            ? ROLE_NOTES[selected.role]
            : 'Admin sets stations, pricing and stock. Managers edit food and stock only. Cashiers run sessions, the till, bookings and members.'}
        </p>
      </section>
    </div>
  );
}
