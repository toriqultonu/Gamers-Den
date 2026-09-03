/**
 * The auto-lock screen and the PIN-failure vocabulary behind it
 * (design.md §6 "Auto-lock (PIN to unlock)", §1 S1 row).
 *
 * The lock is the same PIN check as S1, so it renders the same three answers:
 * in, wrong with a count, or locked out.
 */

import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { LockScreen } from '@/components/domain/lock-screen';
import { ApiError } from '@/lib/api';
import { lockoutMessage, pinAttemptFrom } from '@/features/auth/pin-errors';
import type { SignedInStaff } from '@/features/auth/session';

const STAFF: SignedInStaff = {
  id: 4,
  name: 'Sabbir Ahmed',
  role: 'CASHIER',
  avatarColor: null,
};

function renderLock(overrides: Partial<React.ComponentProps<typeof LockScreen>> = {}) {
  const onUnlock = overrides.onUnlock ?? vi.fn().mockResolvedValue(undefined);
  const onSignOut = overrides.onSignOut ?? vi.fn();
  render(
    <LockScreen staff={STAFF} terminal="T1" onUnlock={onUnlock} onSignOut={onSignOut} />,
  );
  return { onUnlock, onSignOut };
}

describe('the lock screen', () => {
  it('names who is locked out of their own terminal', () => {
    renderLock();
    expect(screen.getByRole('dialog', { name: 'Terminal locked' })).toBeInTheDocument();
    expect(screen.getByText('Sabbir Ahmed')).toBeInTheDocument();
    expect(screen.getByText('Cashier · T1')).toBeInTheDocument();
  });

  it('will not unlock on a partial PIN', async () => {
    const user = userEvent.setup();
    const { onUnlock } = renderLock();

    await user.type(screen.getByLabelText('Enter your PIN to unlock'), '04');
    expect(screen.getByRole('button', { name: 'Unlock' })).toBeDisabled();
    expect(onUnlock).not.toHaveBeenCalled();
  });

  it('hands the PIN over once it is complete', async () => {
    const user = userEvent.setup();
    const { onUnlock } = renderLock();

    await user.type(screen.getByLabelText('Enter your PIN to unlock'), '0417');
    await user.click(screen.getByRole('button', { name: 'Unlock' }));

    await waitFor(() => expect(onUnlock).toHaveBeenCalledWith('0417'));
  });

  it('says a wrong PIN inline and clears it for the next try', async () => {
    const user = userEvent.setup();
    const onUnlock = vi.fn().mockRejectedValue(
      new ApiError({
        status: 401,
        code: 'UNAUTHORIZED',
        message: 'Wrong staff id or PIN',
        details: { attemptsRemaining: 2 },
      }),
    );
    renderLock({ onUnlock });

    await user.type(screen.getByLabelText('Enter your PIN to unlock'), '9999');
    await user.click(screen.getByRole('button', { name: 'Unlock' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Wrong PIN — 2 tries left.');
    expect(screen.getByLabelText('Enter your PIN to unlock')).toHaveValue('');
  });

  it('closes the pad once the account locks, leaving only the way out', async () => {
    const user = userEvent.setup();
    const onUnlock = vi.fn().mockRejectedValue(
      new ApiError({
        status: 423,
        code: 'LOCKED_PIN',
        message: 'PIN locked after 5 failed attempts',
        details: { lockedUntil: '2026-09-03T21:34:00+06:00', retryAfterSeconds: 900 },
      }),
    );
    renderLock({ onUnlock });

    await user.type(screen.getByLabelText('Enter your PIN to unlock'), '9999');
    await user.click(screen.getByRole('button', { name: 'Unlock' }));

    await waitFor(() =>
      expect(screen.getByLabelText('Enter your PIN to unlock')).toBeDisabled(),
    );
    expect(screen.getByRole('button', { name: 'Unlock' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Sign out instead' })).toBeEnabled();
  });

  it('offers a sign-out for the person who is not the one who walked away', async () => {
    const user = userEvent.setup();
    const { onSignOut } = renderLock();

    await user.click(screen.getByRole('button', { name: 'Sign out instead' }));
    expect(onSignOut).toHaveBeenCalled();
  });
});

describe('reading a refused PIN', () => {
  it('takes the remaining-try count from the envelope rather than counting itself', () => {
    const attempt = pinAttemptFrom(
      new ApiError({
        status: 401,
        code: 'UNAUTHORIZED',
        message: 'nope',
        details: { attemptsRemaining: 4 },
      }),
    );
    expect(attempt).toMatchObject({ kind: 'wrong', attemptsRemaining: 4 });
  });

  it('falls back to a bare "Wrong PIN." when the server sends no count', () => {
    const attempt = pinAttemptFrom(
      new ApiError({ status: 401, code: 'UNAUTHORIZED', message: 'nope' }),
    );
    expect(attempt).toMatchObject({ kind: 'wrong', message: 'Wrong PIN.' });
  });

  it('recognises the lockout by code and by status', () => {
    expect(
      pinAttemptFrom(new ApiError({ status: 423, code: 'LOCKED_PIN', message: 'locked' })).kind,
    ).toBe('locked');
    expect(
      pinAttemptFrom(new ApiError({ status: 423, code: 'CONFLICT', message: 'locked' })).kind,
    ).toBe('locked');
  });

  it('says the venue box is unreachable rather than blaming the PIN', () => {
    expect(
      pinAttemptFrom(new ApiError({ status: 0, code: 'NETWORK_ERROR', message: 'x' })),
    ).toMatchObject({ kind: 'error', message: 'Cannot reach the venue server.' });
  });

  it('degrades to prose for anything thrown that is not an ApiError', () => {
    expect(pinAttemptFrom(new TypeError('boom')).kind).toBe('error');
  });
});

describe('the lockout message', () => {
  it('gives both the reopening time and the wait when the server sends both', () => {
    expect(lockoutMessage('2026-09-03T21:34:00+06:00', 900)).toBe(
      'Locked after 5 wrong PINs — try again at 21:34 (15 min).',
    );
  });

  it('gives whichever half it has', () => {
    expect(lockoutMessage('2026-09-03T21:34:00+06:00', null)).toBe(
      'Locked after 5 wrong PINs — try again at 21:34.',
    );
    expect(lockoutMessage(null, 300)).toBe('Locked after 5 wrong PINs — try again in 5 min.');
    expect(lockoutMessage(null, null)).toBe(
      'Locked after 5 wrong PINs — try again in 15 minutes.',
    );
  });
});
