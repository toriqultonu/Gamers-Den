import { cookies } from 'next/headers';
import { SetupScreen } from '@/components/domain/setup-screen';
import { SESSION_COOKIE, decodeSessionCookie } from '@/lib/session-cookie';

/**
 * S10 — Setup / Menu & stock (TASK F13).
 *
 * A server component that reads one thing — the role the middleware just
 * checked — and hands it to the screen, the way `(app)/layout.tsx` does for the
 * sidebar and S12's page does for its rails. The role decides which sections
 * render: Admin gets stations, pricing, staff and the pre-booking switches on
 * top of menu & stock; Manager gets menu & stock (frontend/ARCHITECTURE.md
 * §4.3, "S10 role-sectioned — booking controls admin-only").
 *
 * It stays a hint. Every write behind those sections is re-checked by the API,
 * which answers 403 whatever was drawn.
 */
export default async function SetupPage() {
  const store = await cookies();
  const role = decodeSessionCookie(store.get(SESSION_COOKIE)?.value);
  return <SetupScreen role={role} />;
}
