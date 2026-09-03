import { cookies } from 'next/headers';
import { TournamentsScreen } from '@/components/domain/tournaments-screen';
import { SESSION_COOKIE, decodeSessionCookie } from '@/lib/session-cookie';

/**
 * S12 — Tournaments (TASK F11).
 *
 * A server component that reads one thing — the role the middleware just
 * checked — and hands it to the screen, the way `(app)/layout.tsx` does for the
 * sidebar. It decides which rail renders and whether the finance query mounts
 * (frontend/ARCHITECTURE.md §4.3: "S12 renders for all roles; manager rail +
 * finance query mount only for Manager+"). It stays a hint: every write behind
 * those controls is re-checked by the API, which answers 403 whatever was
 * drawn.
 */
export default async function TournamentsPage() {
  const store = await cookies();
  const role = decodeSessionCookie(store.get(SESSION_COOKIE)?.value);
  return <TournamentsScreen role={role} />;
}
