/**
 * The permission-denied state every screen owes (design.md §1: "UI hides
 * affordance AND API 403 renders as an access notice").
 *
 * The middleware already keeps a cashier off S2 and S9, so this is what shows
 * when the routing hint and the real role disagree — a role changed in S10
 * mid-shift, a stale cookie, a hand-typed URL. It explains rather than
 * apologises, and offers the way back.
 */

import Link from 'next/link';
import type { Route } from 'next';
import { ShieldAlert } from 'lucide-react';

export type AccessNoticeProps = {
  /** What was refused, e.g. "Overview". */
  screen?: string;
  /** Where "Back" goes — the role's landing screen. */
  backHref?: Route;
  message?: string;
};

export function AccessNotice({ screen, backHref = '/floor', message }: AccessNoticeProps) {
  return (
    <section
      role="alert"
      data-testid="access-notice"
      className="m-6 flex max-w-xl flex-col gap-3 border-2 border-divider p-6"
    >
      <p className="type-label flex items-center gap-2 text-accent-strong">
        <ShieldAlert aria-hidden="true" className="size-4" strokeWidth={2} />
        Access denied
      </p>
      <h2 className="text-h3">{screen ? `${screen} is not open to your role` : 'Not your screen'}</h2>
      <p className="text-body opacity-75">
        {message ??
          'Your role does not have access to this. Ask the owner to change it in Setup, or carry on where you were.'}
      </p>
      <Link href={backHref} className="text-body text-accent-strong underline underline-offset-4">
        Back to your screens
      </Link>
    </section>
  );
}
