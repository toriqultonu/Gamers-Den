import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';

/**
 * Auth-cookie guard for the `(app)` routes, plus the S2/S9/S10 role redirects.
 * Scaffolded in TASK F01; built in TASK F04 — it deliberately does nothing yet
 * so the scaffold cannot lock anyone out of the stub screens.
 */
export function middleware(_request: NextRequest) {
  return NextResponse.next();
}

export const config = {
  matcher: ['/((?!_next/static|_next/image|favicon.ico).*)'],
};
