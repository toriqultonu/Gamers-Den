/**
 * `middleware.ts` — the cookie guard and the role redirects
 * (frontend/ARCHITECTURE.md §4.3).
 *
 * The cookie carries a role and nothing else, so these tests are about routing
 * only: where an unsigned browser is sent, where a signed-in one is sent back
 * from, and which roles bounce off S2 / S9 / S10. The API 403s regardless —
 * that is not this file's job to prove.
 */

import { describe, expect, it } from 'vitest';
import { NextRequest } from 'next/server';
import { middleware } from '@/middleware';
import { SESSION_COOKIE } from '@/lib/session-cookie';
import type { Role } from '@/lib/nav';

const ORIGIN = 'http://terminal.local';

function visit(path: string, role?: Role | string) {
  const headers = new Headers();
  if (role) headers.set('cookie', `${SESSION_COOKIE}=${role}`);
  return middleware(new NextRequest(new URL(path, ORIGIN), { headers }));
}

/** The path a redirect points at, or null when the request was let through. */
function redirectOf(response: ReturnType<typeof middleware>): string | null {
  const location = response.headers.get('location');
  return location ? new URL(location).pathname + new URL(location).search : null;
}

function passedThrough(response: ReturnType<typeof middleware>): boolean {
  return response.headers.get('location') === null;
}

describe('with no session cookie', () => {
  it('sends an app route to S1 and remembers where they were going', () => {
    expect(redirectOf(visit('/floor'))).toBe('/login?next=%2Ffloor');
  });

  it('keeps the query string of the screen it interrupted', () => {
    expect(redirectOf(visit('/bookings?tab=history'))).toBe(
      '/login?next=%2Fbookings%3Ftab%3Dhistory',
    );
  });

  it('sends "/" to S1 with nothing to come back to', () => {
    expect(redirectOf(visit('/'))).toBe('/login');
  });

  it('lets S1 itself through', () => {
    expect(passedThrough(visit('/login'))).toBe(true);
  });

  it('does not guard routes outside the shell', () => {
    expect(passedThrough(visit('/tokens'))).toBe(true);
  });
});

describe('with a session cookie', () => {
  it('lets each role onto the screens it shares with everyone', () => {
    for (const role of ['ADMIN', 'MANAGER', 'CASHIER'] as const) {
      expect(passedThrough(visit('/floor', role))).toBe(true);
      expect(passedThrough(visit('/pos', role))).toBe(true);
    }
  });

  it('sends a signed-in browser off S1 to its own landing screen', () => {
    expect(redirectOf(visit('/login', 'ADMIN'))).toBe('/overview');
    expect(redirectOf(visit('/login', 'CASHIER'))).toBe('/floor');
  });

  it('turns "/" into the landing screen', () => {
    expect(redirectOf(visit('/', 'ADMIN'))).toBe('/overview');
    expect(redirectOf(visit('/', 'MANAGER'))).toBe('/floor');
  });

  it('keeps S2 to the owner', () => {
    expect(passedThrough(visit('/overview', 'ADMIN'))).toBe(true);
    expect(redirectOf(visit('/overview', 'MANAGER'))).toBe('/floor');
    expect(redirectOf(visit('/overview', 'CASHIER'))).toBe('/floor');
  });

  it('keeps S9 to manager and up', () => {
    expect(passedThrough(visit('/reports', 'MANAGER'))).toBe(true);
    expect(redirectOf(visit('/reports', 'CASHIER'))).toBe('/floor');
  });

  it('keeps S10 to manager and up', () => {
    expect(passedThrough(visit('/setup', 'MANAGER'))).toBe(true);
    expect(redirectOf(visit('/setup', 'CASHIER'))).toBe('/floor');
  });

  it('guards nested screens the same way as their parent', () => {
    expect(passedThrough(visit('/print/42', 'CASHIER'))).toBe(true);
  });

  it('treats a cookie it cannot read as no cookie at all', () => {
    expect(redirectOf(visit('/floor', 'OWNER'))).toBe('/login?next=%2Ffloor');
    expect(redirectOf(visit('/floor', ''))).toBe('/login?next=%2Ffloor');
  });
});
