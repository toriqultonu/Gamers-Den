import { cookies } from 'next/headers';
import { AppShell } from '@/components/domain/app-shell';
import { SESSION_COOKIE, decodeSessionCookie } from '@/lib/session-cookie';

/**
 * The `(app)` shell — sidebar `NAV[role]` (Bookings gated by
 * `booking_settings.enabled`), topbar, signed-in card, sync chip, auto-lock.
 *
 * A server component that reads one thing: the role the middleware just
 * checked. Handing it to the client shell means the sidebar is right on the
 * first paint instead of appearing a round-trip later — and it stays a hint,
 * replaced by the live session as soon as the refresh lands.
 */
export default async function AppLayout({ children }: { children: React.ReactNode }) {
  const store = await cookies();
  const role = decodeSessionCookie(store.get(SESSION_COOKIE)?.value);
  return <AppShell initialRole={role}>{children}</AppShell>;
}
