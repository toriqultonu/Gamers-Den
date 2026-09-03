import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';
import { isRouteAllowed, isAppRoute, landingPath } from '@/lib/nav';
import { SESSION_COOKIE, decodeSessionCookie } from '@/lib/session-cookie';

/**
 * The cookie guard for the `(app)` routes plus the role redirects
 * (frontend/ARCHITECTURE.md §4.3).
 *
 * Three rules, in order:
 *
 *  1. no session hint on an `(app)` route → S1, remembering where they meant
 *     to go so the sign-in lands there;
 *  2. a session hint on S1 → straight to that role's landing screen (S2 for
 *     the owner, S3 for everyone else) — signing in twice is never the intent;
 *  3. a role standing somewhere it may not be (S2 as a cashier, S9 as a
 *     cashier) → its own landing screen.
 *
 * This is routing, not security: the cookie only carries a role, and the API
 * 403s regardless of what the browser claims. The shell renders that 403 as an
 * access notice (design.md §1) for the case where this hint is stale.
 */
export function middleware(request: NextRequest) {
  const { pathname, search } = request.nextUrl;
  const role = decodeSessionCookie(request.cookies.get(SESSION_COOKIE)?.value);

  if (pathname === '/login') {
    return role ? redirectTo(request, landingPath(role)) : NextResponse.next();
  }

  // "/" is not a screen — it is whichever screen this role starts on.
  if (pathname === '/') {
    return role ? redirectTo(request, landingPath(role)) : redirectToLogin(request, '/');
  }

  if (!isAppRoute(pathname)) return NextResponse.next();

  if (!role) return redirectToLogin(request, `${pathname}${search}`);

  if (!isRouteAllowed(role, pathname)) return redirectTo(request, landingPath(role));

  return NextResponse.next();
}

function redirectTo(request: NextRequest, pathname: string) {
  const url = request.nextUrl.clone();
  url.pathname = pathname;
  url.search = '';
  return NextResponse.redirect(url);
}

function redirectToLogin(request: NextRequest, next: string) {
  const url = request.nextUrl.clone();
  url.pathname = '/login';
  url.search = next && next !== '/' ? `?next=${encodeURIComponent(next)}` : '';
  return NextResponse.redirect(url);
}

export const config = {
  matcher: ['/((?!_next/static|_next/image|favicon.ico).*)'],
};
