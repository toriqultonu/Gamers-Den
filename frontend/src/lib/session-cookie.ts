/**
 * The cookie `middleware.ts` guards on.
 *
 * The real credentials are elsewhere and deliberately out of reach: the access
 * token lives in memory only (`lib/api.ts`), and the refresh token is an
 * HttpOnly cookie scoped to `/api/v1/auth`, so Next's middleware — which runs
 * on the app origin, not the API's — can never see either.
 *
 * This cookie therefore carries the one thing routing needs and nothing that
 * would be worth stealing: the signed-in role. It is a **routing hint**, not a
 * credential. Forging it buys a redirect, not access — every endpoint behind
 * the screen re-checks the JWT and answers 403 (api-contract.md §1: "UI hiding
 * cosmetic"), and the shell re-derives the role from the restored session.
 */

import { isRole, type Role } from './nav';

export const SESSION_COOKIE = 'gd_session';

/** 12 h — the refresh token's own lifetime; the hint must not outlive it. */
export const SESSION_COOKIE_MAX_AGE = 12 * 60 * 60;

export function decodeSessionCookie(value: string | undefined | null): Role | null {
  return isRole(value) ? value : null;
}

/** `document.cookie` string that plants the hint. Client-side only. */
export function sessionCookieValue(role: Role): string {
  return `${SESSION_COOKIE}=${role}; Path=/; SameSite=Strict; Max-Age=${SESSION_COOKIE_MAX_AGE}`;
}

/** `document.cookie` string that removes it. */
export function clearedSessionCookieValue(): string {
  return `${SESSION_COOKIE}=; Path=/; SameSite=Strict; Max-Age=0`;
}

export function writeSessionCookie(role: Role): void {
  if (typeof document === 'undefined') return;
  document.cookie = sessionCookieValue(role);
}

export function clearSessionCookie(): void {
  if (typeof document === 'undefined') return;
  document.cookie = clearedSessionCookieValue();
}

/** The role this browser last signed in as, read back from `document.cookie`. */
export function readSessionCookie(): Role | null {
  if (typeof document === 'undefined') return null;
  const match = document.cookie
    .split('; ')
    .find((pair) => pair.startsWith(`${SESSION_COOKIE}=`));
  return decodeSessionCookie(match?.slice(SESSION_COOKIE.length + 1));
}
