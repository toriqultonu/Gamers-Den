/**
 * S1 — sign in (docs/design.md §1, S1 row: "Wrong PIN inline; 5-try lockout").
 *
 * The states asserted here are the ones the state table names: the picker, the
 * wrong-PIN line with the server's own count, the lockout that follows the
 * fifth try, and the cold terminal that has nobody to offer yet.
 */

import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LoginScreen } from '@/components/domain/login-screen';
import { SessionProvider } from '@/features/auth/session';
import { STAFF_ROSTER_KEY, readRoster } from '@/features/auth/staff-roster';
import { makeQueryClient } from '@/lib/query-client';
import { SESSION_COOKIE } from '@/lib/session-cookie';
import { forgetSession } from '@/lib/api';

const replace = vi.fn();
let searchParams = new URLSearchParams();

vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace, push: vi.fn(), prefetch: vi.fn(), refresh: vi.fn(), back: vi.fn() }),
  useSearchParams: () => searchParams,
  usePathname: () => '/login',
}));

const fetchMock = vi.fn();

const ADMIN = { id: 1, name: 'Rumi Haque', role: 'ADMIN' as const, avatarColor: '#ec3013' };
const CASHIER = { id: 4, name: 'Sabbir Ahmed', role: 'CASHIER' as const, avatarColor: null };

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

const session = (staff: typeof ADMIN | typeof CASHIER) =>
  json({ accessToken: 'access-token', expiresIn: 900, staff, terminal: 'T1', tokenType: 'Bearer' });

const wrongPin = (attemptsRemaining: number) =>
  json(
    {
      error: {
        code: 'UNAUTHORIZED',
        message: 'Wrong staff id or PIN',
        details: { attemptsRemaining },
      },
    },
    401,
  );

const lockedOut = () =>
  json(
    {
      error: {
        code: 'LOCKED_PIN',
        message: 'PIN locked after 5 failed attempts',
        details: {
          staffId: 1,
          lockedUntil: '2026-09-03T21:34:00+06:00',
          retryAfterSeconds: 900,
        },
      },
    },
    423,
  );

function renderLogin() {
  return render(
    <QueryClientProvider client={makeQueryClient()}>
      <SessionProvider>
        <LoginScreen />
      </SessionProvider>
    </QueryClientProvider>,
  );
}

/** The body of the n-th `POST /auth/login`. */
function loginBody(index = 0): Record<string, unknown> {
  const [, init] = fetchMock.mock.calls[index] as [string, RequestInit];
  return JSON.parse(String(init.body)) as Record<string, unknown>;
}

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock);
  fetchMock.mockReset();
  replace.mockReset();
  searchParams = new URLSearchParams();
  window.localStorage.clear();
  document.cookie = `${SESSION_COOKIE}=; Path=/; Max-Age=0`;
  forgetSession();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('the staff picker', () => {
  it('offers the people who have signed in on this terminal', async () => {
    window.localStorage.setItem(STAFF_ROSTER_KEY, JSON.stringify([ADMIN, CASHIER]));
    renderLogin();

    expect(await screen.findByText('Rumi Haque')).toBeInTheDocument();
    expect(screen.getByText('Sabbir Ahmed')).toBeInTheDocument();
    // The most recent sign-in is pre-selected, so the usual case is PIN-only.
    expect(await screen.findByTestId('staff-option-1')).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByTestId('staff-option-4')).toHaveAttribute('aria-checked', 'false');
  });

  it('falls back to a staff-id field on a terminal that has nobody yet', async () => {
    renderLogin();

    expect(await screen.findByLabelText('Staff ID')).toBeInTheDocument();
    expect(screen.queryByRole('radiogroup')).not.toBeInTheDocument();
  });

  it('signs in whoever was picked, on this terminal', async () => {
    const user = userEvent.setup();
    window.localStorage.setItem(STAFF_ROSTER_KEY, JSON.stringify([ADMIN, CASHIER]));
    fetchMock.mockResolvedValueOnce(session(CASHIER));
    renderLogin();

    await user.click(await screen.findByTestId('staff-option-4'));
    await user.type(screen.getByLabelText('PIN'), '0417');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    await waitFor(() => expect(replace).toHaveBeenCalled());
    expect(loginBody()).toEqual({ staffId: 4, pin: '0417', terminal: 'T1' });
  });
});

describe('a successful sign-in', () => {
  it('lands the owner on S2 and remembers them for next time', async () => {
    const user = userEvent.setup();
    window.localStorage.setItem(STAFF_ROSTER_KEY, JSON.stringify([ADMIN]));
    fetchMock.mockResolvedValueOnce(session(ADMIN));
    renderLogin();

    await user.type(await screen.findByLabelText('PIN'), '1234');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    await waitFor(() => expect(replace).toHaveBeenCalledWith('/overview'));
    expect(document.cookie).toContain(`${SESSION_COOKIE}=ADMIN`);
    expect(readRoster()[0]).toMatchObject({ id: 1, name: 'Rumi Haque', role: 'ADMIN' });
  });

  it('lands a cashier on S3', async () => {
    const user = userEvent.setup();
    window.localStorage.setItem(STAFF_ROSTER_KEY, JSON.stringify([CASHIER]));
    fetchMock.mockResolvedValueOnce(session(CASHIER));
    renderLogin();

    await user.type(await screen.findByLabelText('PIN'), '0417');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    await waitFor(() => expect(replace).toHaveBeenCalledWith('/floor'));
  });

  it('returns to the screen the guard interrupted', async () => {
    const user = userEvent.setup();
    searchParams = new URLSearchParams('next=%2Fbookings');
    window.localStorage.setItem(STAFF_ROSTER_KEY, JSON.stringify([CASHIER]));
    fetchMock.mockResolvedValueOnce(session(CASHIER));
    renderLogin();

    await user.type(await screen.findByLabelText('PIN'), '0417');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    await waitFor(() => expect(replace).toHaveBeenCalledWith('/bookings'));
  });

  it('refuses an off-site "next" rather than following it', async () => {
    const user = userEvent.setup();
    searchParams = new URLSearchParams('next=%2F%2Fevil.example%2Fsteal');
    window.localStorage.setItem(STAFF_ROSTER_KEY, JSON.stringify([CASHIER]));
    fetchMock.mockResolvedValueOnce(session(CASHIER));
    renderLogin();

    await user.type(await screen.findByLabelText('PIN'), '0417');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    await waitFor(() => expect(replace).toHaveBeenCalledWith('/floor'));
  });
});

describe('a wrong PIN', () => {
  it('says so inline, with the tries the server says are left', async () => {
    const user = userEvent.setup();
    window.localStorage.setItem(STAFF_ROSTER_KEY, JSON.stringify([ADMIN]));
    fetchMock.mockResolvedValueOnce(wrongPin(3));
    renderLogin();

    await user.type(await screen.findByLabelText('PIN'), '9999');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Wrong PIN — 3 tries left.');
    expect(replace).not.toHaveBeenCalled();
    expect(screen.queryByTestId('lockout-notice')).not.toBeInTheDocument();
  });

  it('warns in the singular on the last try', async () => {
    const user = userEvent.setup();
    window.localStorage.setItem(STAFF_ROSTER_KEY, JSON.stringify([ADMIN]));
    fetchMock.mockResolvedValueOnce(wrongPin(1));
    renderLogin();

    await user.type(await screen.findByLabelText('PIN'), '9999');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Wrong PIN — 1 try left before this account locks.',
    );
  });

  it('clears the PIN but keeps the identity — an error never undoes the choice', async () => {
    const user = userEvent.setup();
    window.localStorage.setItem(STAFF_ROSTER_KEY, JSON.stringify([ADMIN, CASHIER]));
    fetchMock.mockResolvedValueOnce(wrongPin(4));
    renderLogin();

    await user.click(await screen.findByTestId('staff-option-4'));
    await user.type(screen.getByLabelText('PIN'), '9999');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    await screen.findByRole('alert');
    expect(screen.getByLabelText('PIN')).toHaveValue('');
    expect(screen.getByTestId('staff-option-4')).toHaveAttribute('aria-checked', 'true');
  });
});

describe('the 5-try lockout', () => {
  it('renders the lockout state with the time the account reopens', async () => {
    const user = userEvent.setup();
    window.localStorage.setItem(STAFF_ROSTER_KEY, JSON.stringify([ADMIN]));
    fetchMock.mockResolvedValueOnce(lockedOut());
    renderLogin();

    await user.type(await screen.findByLabelText('PIN'), '9999');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    const notice = await screen.findByTestId('lockout-notice');
    expect(notice).toHaveTextContent('Locked after 5 wrong PINs — try again at 21:34 (15 min).');
  });

  it('closes the form so the sixth try cannot be made', async () => {
    const user = userEvent.setup();
    window.localStorage.setItem(STAFF_ROSTER_KEY, JSON.stringify([ADMIN]));
    fetchMock.mockResolvedValueOnce(lockedOut());
    renderLogin();

    await user.type(await screen.findByLabelText('PIN'), '9999');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));
    await screen.findByTestId('lockout-notice');

    expect(screen.getByLabelText('PIN')).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeDisabled();
    expect(screen.getByTestId('staff-option-1')).toBeDisabled();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
