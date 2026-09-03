/**
 * What a refused PIN means on screen — design.md §1, S1 row: "Wrong PIN
 * inline; 5-try lockout".
 *
 * The backend does the counting and says so in the envelope: a 401 carries
 * `attemptsRemaining`, and the fifth failure turns into 423 `LOCKED_PIN` with
 * `lockedUntil` and `retryAfterSeconds`. The screen therefore never guesses
 * how many tries are left — it reads them.
 *
 * Shared by S1 and the auto-lock screen, because both are the same PIN check.
 */

import { isApiError } from '@/lib/api';
import { formatVenueTime } from '@/lib/time';

export type PinAttempt =
  | { kind: 'idle' }
  | { kind: 'wrong'; message: string; attemptsRemaining: number | null }
  | { kind: 'locked'; message: string; lockedUntil: string | null; retryAfterSeconds: number | null }
  | { kind: 'error'; message: string };

export const PIN_IDLE: PinAttempt = { kind: 'idle' };

function numberOr(value: unknown, fallback: number | null): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

function stringOr(value: unknown, fallback: string | null): string | null {
  return typeof value === 'string' && value ? value : fallback;
}

/** Turns a thrown login failure into the state S1 and the lock screen render. */
export function pinAttemptFrom(error: unknown): PinAttempt {
  if (!isApiError(error)) {
    return { kind: 'error', message: 'Something went wrong signing in. Try again.' };
  }

  if (error.code === 'LOCKED_PIN' || error.status === 423) {
    const lockedUntil = stringOr(error.details?.lockedUntil, null);
    const retryAfterSeconds = numberOr(error.details?.retryAfterSeconds, null);
    return {
      kind: 'locked',
      message: lockoutMessage(lockedUntil, retryAfterSeconds),
      lockedUntil,
      retryAfterSeconds,
    };
  }

  if (error.status === 401) {
    const attemptsRemaining = numberOr(error.details?.attemptsRemaining, null);
    return {
      kind: 'wrong',
      message:
        attemptsRemaining === null
          ? 'Wrong PIN.'
          : attemptsRemaining === 1
            ? 'Wrong PIN — 1 try left before this account locks.'
            : `Wrong PIN — ${attemptsRemaining} tries left.`,
      attemptsRemaining,
    };
  }

  if (error.code === 'NETWORK_ERROR') {
    return { kind: 'error', message: 'Cannot reach the venue server.' };
  }

  return { kind: 'error', message: error.message };
}

/** "Locked after 5 wrong PINs — try again at 21:34 (15 min)." */
export function lockoutMessage(
  lockedUntil: string | null,
  retryAfterSeconds: number | null,
): string {
  const at = lockedUntil ? formatVenueTime(lockedUntil) : null;
  const minutes =
    retryAfterSeconds !== null ? Math.max(1, Math.ceil(retryAfterSeconds / 60)) : null;
  if (at && minutes !== null) return `Locked after 5 wrong PINs — try again at ${at} (${minutes} min).`;
  if (at) return `Locked after 5 wrong PINs — try again at ${at}.`;
  if (minutes !== null) return `Locked after 5 wrong PINs — try again in ${minutes} min.`;
  return 'Locked after 5 wrong PINs — try again in 15 minutes.';
}

export function isLockedOut(attempt: PinAttempt): attempt is Extract<PinAttempt, { kind: 'locked' }> {
  return attempt.kind === 'locked';
}
