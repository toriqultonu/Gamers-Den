import { PrintPreviewScreen } from '@/components/domain/print-preview-screen';

/**
 * S11 — Print preview (TASK F15).
 *
 * A server component whose only job is the URL: the id arrives as a string and
 * the screen wants a job number, so a hand-typed `/print/abc` becomes a notice
 * rather than a query for job `NaN`. Everything else — the stored render, the
 * job's state, retry and reprint — is the client screen's, because the job is
 * the one thing here that moves while the paper does.
 *
 * Reached from a settle, a shift close, or a ticket's own job; it has no nav
 * item, and the cookie guard covers it like any `(app)` route (`lib/nav.ts`).
 */
export default async function PrintPreviewPage({
  params,
}: {
  params: Promise<{ jobId: string }>;
}) {
  const { jobId } = await params;
  const id = Number(jobId);
  return <PrintPreviewScreen jobId={Number.isInteger(id) && id > 0 ? id : null} />;
}
