import { cookies } from 'next/headers';
import { OverviewScreen } from '@/components/domain/overview-screen';
import { SESSION_COOKIE, decodeSessionCookie } from '@/lib/session-cookie';

/**
 * S2 — Overview (TASK F14).
 *
 * Admin only. `middleware.ts` already sent a manager or a cashier to their own
 * landing screen (`ROUTE_ROLES['/overview']`); this reads the same cookie so
 * the screen can render the access notice rather than firing an Admin-only
 * query when the routing hint and the real role disagree (§4.3).
 */
export default async function OverviewPage() {
  const store = await cookies();
  const role = decodeSessionCookie(store.get(SESSION_COOKIE)?.value);
  return <OverviewScreen role={role} />;
}
