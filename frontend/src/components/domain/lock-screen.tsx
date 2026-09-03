'use client';

/**
 * The auto-lock overlay — S13's "Auto-lock (PIN to unlock)".
 *
 * It covers the shell rather than replacing it: the session, the open bill and
 * every query stay exactly as they were, and the same PIN that signed this
 * person in lifts it. Signing out is offered too, because the person standing
 * there may not be the one who walked away.
 */

import { useState } from 'react';
import { Lock } from 'lucide-react';
import { AvatarSwatch, Button } from '@/components/ui';
import { PinPad, PIN_LENGTH } from './pin-pad';
import { initialsOf } from './signed-in-card';
import { ROLE_LABELS } from '@/lib/nav';
import { PIN_IDLE, pinAttemptFrom, type PinAttempt } from '@/features/auth/pin-errors';
import type { SignedInStaff } from '@/features/auth/session';

export type LockScreenProps = {
  staff: SignedInStaff;
  terminal: string;
  onUnlock: (pin: string) => Promise<void>;
  onSignOut: () => Promise<void> | void;
};

export function LockScreen({ staff, terminal, onUnlock, onSignOut }: LockScreenProps) {
  const [pin, setPin] = useState('');
  const [attempt, setAttempt] = useState<PinAttempt>(PIN_IDLE);
  const [busy, setBusy] = useState(false);

  const locked = attempt.kind === 'locked';
  const canSubmit = pin.length === PIN_LENGTH && !busy && !locked;

  const submit = async () => {
    if (!canSubmit) return;
    setBusy(true);
    setAttempt(PIN_IDLE);
    try {
      await onUnlock(pin);
      setPin('');
    } catch (error) {
      setAttempt(pinAttemptFrom(error));
      setPin('');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="Terminal locked"
      data-testid="lock-screen"
      className="fixed inset-0 z-50 grid place-items-center bg-bg/95 p-6 backdrop-blur-sm"
    >
      <div className="flex w-full max-w-sm flex-col gap-5 border-2 border-divider bg-surface p-6 shadow-lg">
        <div className="flex items-center gap-3">
          <AvatarSwatch color={staff.avatarColor} initials={initialsOf(staff.name)} size="lg" />
          <div className="min-w-0">
            <p className="type-label flex items-center gap-1.5 text-accent-strong">
              <Lock aria-hidden="true" className="size-3.5" strokeWidth={2} />
              Terminal locked
            </p>
            <p className="truncate font-heading text-h3">{staff.name}</p>
            <p className="truncate text-[12px] opacity-55">
              {ROLE_LABELS[staff.role]} · {terminal}
            </p>
          </div>
        </div>

        <hr className="rule" />

        {locked ? (
          <p role="alert" className="border-2 border-accent bg-accent-tint p-3 text-body text-accent-800">
            {attempt.message}
          </p>
        ) : null}

        <PinPad
          value={pin}
          onChange={setPin}
          onSubmit={submit}
          autoFocus
          disabled={busy || locked}
          error={attempt.kind === 'wrong' || attempt.kind === 'error' ? attempt.message : undefined}
          label="Enter your PIN to unlock"
        />

        <Button variant="block" size="lg" loading={busy} disabled={!canSubmit} onClick={submit}>
          Unlock
        </Button>

        <Button variant="ghost" onClick={() => void onSignOut()}>
          Sign out instead
        </Button>
      </div>
    </div>
  );
}
