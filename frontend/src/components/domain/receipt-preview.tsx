'use client';

/**
 * ReceiptPreview — docs/design.md §2: variants "receipt, z/x-report, tournament
 * stub, play-ticket stub"; states "rendering, ready, failed"; prop `printJobId`.
 *
 * **The paper is the server's.** This draws `GET /print-jobs/{id}/render` — the
 * stored 48-column character grid produced by the same pass that produced the
 * ESC/POS bytes — and never a receipt of its own (invariant §5.6, and
 * design.md §5: "shows the stored render (never recomputed)"). So the P1 sale
 * ticket, the P5 tournament stub and the P6 play ticket all arrive already
 * drawn; nothing here knows what was sold.
 *
 * What *is* drawn here is the on-screen band above the paper: the tokens the
 * settle issued. They are on the paper too, but a token is what the customer is
 * about to be told out loud — "you're number four" — and the operator should not
 * have to read it out of a monospace block (design.md §2, TokenBadge; §5.12,
 * tokens are queue identity).
 *
 * The job's own state rides alongside: QUEUED means the printer has not taken
 * it yet, FAILED names the thing to fix. Retrying a failed job is S11's, with
 * its reprint reasons (F15).
 */

import Link from 'next/link';
import { TokenBadge } from '@/components/ui/token-badge';
import { cn } from '@/components/ui/cn';
import {
  printFailureNotice,
  renderLines,
  usePrintJob,
  usePrintRender,
  type PrintJob,
} from '@/features/printing/use-print-job';
import { errorNotice } from '@/lib/api';
import type { SettleResult } from '@/features/payments/schemas';

export const RECEIPT_PREVIEW_STATES = ['rendering', 'ready', 'failed'] as const;
export type ReceiptPreviewState = (typeof RECEIPT_PREVIEW_STATES)[number];

export type ReceiptPreviewProps = {
  /** The job to draw — from `POST /payments` → `printJobId`. */
  printJobId: number | null;
  /** The tokens that settle issued, shown above the paper. */
  result?: SettleResult | null;
  /** Today in the venue timezone, so a token from yesterday says so. */
  today?: string;
  className?: string;
};

export function ReceiptPreview({ printJobId, result, today, className }: ReceiptPreviewProps) {
  const job = usePrintJob(printJobId);
  const render = usePrintRender(printJobId);

  const lines = renderLines(render.data);
  const state = previewState(render.isPending, render.isError, lines.length);
  const queueTokens = result?.queueTokens ?? [];
  const entryTokens = result?.entryTokens ?? [];

  return (
    <section
      data-testid="receipt-preview"
      data-state={state}
      className={cn('flex flex-col gap-2.5', className)}
    >
      <h2 className="type-label opacity-55">80 mm thermal ticket</h2>

      {queueTokens.length > 0 ? (
        <div data-testid="queue-tokens" className="flex flex-col gap-1.5 border-2 border-divider p-2.5">
          <h3 className="type-label opacity-55">Play tickets · queue tokens</h3>
          <div className="flex flex-wrap gap-1.5">
            {queueTokens.map((token) => (
              <TokenBadge
                key={token.queueEntryId ?? token.tokenNo}
                token={token.tokenNo ?? 0}
                issuedOn={token.tokenDate}
                today={today}
              />
            ))}
          </div>
          <p className="text-[11px] opacity-55">
            Seat them from the Floor queue rail. Tokens reset daily.
          </p>
        </div>
      ) : null}

      {entryTokens.length > 0 ? (
        <div data-testid="entry-tokens" className="flex flex-col gap-1.5 border-2 border-divider p-2.5">
          <h3 className="type-label opacity-55">
            {entryTokens.length === 1 ? 'Tournament entry' : `Tournament entries · ${entryTokens.length}`}
          </h3>
          <ul className="flex flex-col gap-1">
            {entryTokens.map((token) => (
              <li key={token} data-testid="entry-token" className="tabular type-mono text-[11px] break-all">
                {token}
              </li>
            ))}
          </ul>
          <p className="text-[11px] opacity-55">
            The stub&apos;s QR is their bracket pass — show it at the desk.
          </p>
        </div>
      ) : null}

      {printJobId === null ? (
        <p data-testid="receipt-idle" className="text-[12px] opacity-60">
          The ticket prints itself when the payment is taken — this panel then shows the
          artifact exactly as it came off the printer.
        </p>
      ) : state === 'failed' ? (
        <p role="alert" data-testid="receipt-error" className="border-2 border-accent px-2.5 py-2 text-[12px] text-accent-strong">
          {errorNotice(render.error, 'The stored render could not be read.')}
        </p>
      ) : state === 'rendering' ? (
        <div data-testid="receipt-skeleton" aria-busy="true" className="flex flex-col gap-1.5 bg-paper p-4">
          {[0, 1, 2, 3, 4, 5, 6].map((row) => (
            <div key={row} className="h-2.5 bg-track" style={{ width: `${90 - row * 7}%` }} />
          ))}
        </div>
      ) : (
        <pre
          data-testid="receipt-render"
          data-columns={render.data?.columns ?? 48}
          className="overflow-x-auto bg-paper px-3.5 py-4 type-mono text-[#201e1d] shadow-md"
        >
          {lines.join('\n')}
        </pre>
      )}

      {printJobId !== null ? (
        <>
          <JobStatus job={job.data} pending={job.isPending} />
          {/* design.md §1 lists "POS settle" as an S11 entry point: the reprint
              reasons and the retry live there, on the job (F15). */}
          <Link
            href={{ pathname: `/print/${printJobId}` }}
            data-testid="open-print-preview"
            className="text-[12px] text-accent-strong underline underline-offset-4"
          >
            Open print preview
          </Link>
        </>
      ) : null}
    </section>
  );
}

/** design.md §2: rendering while the stored text is being read, then ready or failed. */
export function previewState(
  pending: boolean,
  errored: boolean,
  lineCount: number,
): ReceiptPreviewState {
  if (errored) return 'failed';
  if (pending || lineCount === 0) return 'rendering';
  return 'ready';
}

/** Where the paper actually is — QUEUED behind an offline printer, or FAILED. */
function JobStatus({ job, pending }: { job: PrintJob | undefined; pending: boolean }) {
  if (pending || !job) {
    return (
      <p data-testid="print-job-status" data-status="PENDING" className="text-[11px] opacity-55">
        Checking the printer…
      </p>
    );
  }

  const status = job.status ?? 'QUEUED';
  const failed = status === 'FAILED';

  return (
    <p
      role={failed ? 'alert' : undefined}
      data-testid="print-job-status"
      data-status={status}
      className={failed ? 'text-[12px] text-accent-strong' : 'text-[11px] opacity-55'}
    >
      {failed
        ? `${printFailureNotice(job)} Reprint it from the print preview.`
        : status === 'DONE'
          ? `Printed${job.attempts && job.attempts > 1 ? ` after ${job.attempts} attempts` : ''}.`
          : status === 'PRINTING'
            ? 'Printing…'
            : 'Queued — it prints as soon as the printer answers.'}
    </p>
  );
}
