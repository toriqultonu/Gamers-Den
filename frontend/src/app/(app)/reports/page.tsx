import { cookies } from 'next/headers';
import { ReportsScreen } from '@/components/domain/reports-screen';
import { SESSION_COOKIE, decodeSessionCookie } from '@/lib/session-cookie';

/**
 * S9 — Reports (TASK F14).
 *
 * Manager+. The middleware keeps a cashier off the route; the role is read here
 * too so a stale cookie earns the access notice instead of a 403 the screen has
 * to catch mid-render (§4.3).
 */
export default async function ReportsPage() {
  const store = await cookies();
  const role = decodeSessionCookie(store.get(SESSION_COOKIE)?.value);
  return <ReportsScreen role={role} />;
}
