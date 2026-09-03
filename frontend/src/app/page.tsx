import { redirect } from 'next/navigation';
import { cookies } from 'next/headers';
import { SESSION_COOKIE, decodeSessionCookie } from '@/lib/session-cookie';
import { landingPath } from '@/lib/nav';

/**
 * "/" is not a screen. The middleware already sends signed-in traffic to the
 * role's landing screen (S2 for the owner, S3 for everyone else); this is the
 * same rule, for the case where the request never went through it.
 */
export default async function RootPage() {
  const store = await cookies();
  const role = decodeSessionCookie(store.get(SESSION_COOKIE)?.value);
  redirect(role ? landingPath(role) : '/login');
}
