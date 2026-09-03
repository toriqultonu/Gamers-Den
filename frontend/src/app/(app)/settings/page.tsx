import { cookies } from 'next/headers';
import { SettingsScreen } from '@/components/domain/settings-screen';
import { SESSION_COOKIE, decodeSessionCookie } from '@/lib/session-cookie';

/**
 * S13 — Settings (TASK F15).
 *
 * A server component that reads one thing — the role the middleware just
 * checked — and hands it to the screen, as S10 and S12 do. Every role opens
 * S13; the role decides only whether the terminal's own controls are writable
 * (`PUT /terminal-settings` is Admin's) or read-only. The profile swatch is
 * everyone's, and the API re-checks both regardless of what was drawn.
 */
export default async function SettingsPage() {
  const store = await cookies();
  const role = decodeSessionCookie(store.get(SESSION_COOKIE)?.value);
  return <SettingsScreen role={role} />;
}
