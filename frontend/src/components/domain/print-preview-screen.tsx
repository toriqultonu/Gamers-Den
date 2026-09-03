'use client';

/**
 * S11 — Print preview (design.md §5, "S11 Print preview").
 *
 * **The paper is the server's.** Everything in the character grid comes from
 * `GET /print-jobs/{id}/render` — the stored 48-column text produced by the
 * same pass that produced the ESC/POS bytes — and nothing here re-computes a
 * receipt, ever (invariant §5.6; design.md §5: "shows the stored render (never
 * recomputed)"). That is what makes this screen an audit rather than a
 * facsimile: what is on screen is what came out of the printer, including the
 * reprint band on a banded copy.
 *
 * The states are design.md §5's, in one place: **rendering** while the stored
 * text is being read · **ready** with Reprint · **queued** behind a printer
 * that has not answered · **failed** with Retry and the thing to fix ·
 * **reprint-mode**, where a reason is required before anything is sent.
 *
 * Retry and reprint are deliberately different buttons for deliberately
 * different acts — see `features/printing/mutations.ts`. There is no bare
 * "Print": every artifact this venue prints already had its job created inside
 * the transaction that produced it, so the only way to put the same document
 * on paper again is a reprint, with a reason, on the record.
 */

import { useState } from 'react';
import Link from 'next/link';
import { AlertTriangle, Printer as PrinterIcon, RotateCcw } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { ChipSelect } from '@/components/ui/chip-select';
import { Tag } from '@/components/ui/tag';
import { AccessNotice } from './access-notice';
import { errorNotice, hasErrorCode, isApiError } from '@/lib/api';
import { useReprintJob, useRetryPrintJob } from '@/features/printing/mutations';
import {
  REPRINT_REASONS,
  REPRINT_REASON_LABELS,
  printJobStatusNote,
  printJobTypeLabel,
  printPreviewState,
  renderLines,
  usePrintJob,
  usePrintRender,
  type PrintJob,
  type ReprintReason,
} from '@/features/printing/use-print-job';

export type PrintPreviewScreenProps = {
  /** The job to draw, or null when the URL carried something that is not one. */
  jobId: number | null;
};

export function PrintPreviewScreen({ jobId }: PrintPreviewScreenProps) {
  const job = usePrintJob(jobId);
  const render = usePrintRender(jobId);
  const retry = useRetryPrintJob();
  const reprint = useReprintJob();

  /** reprint-mode: open, with the reason still unanswered. */
  const [reprinting, setReprinting] = useState(false);
  const [reason, setReason] = useState<ReprintReason | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [reprintedAs, setReprintedAs] = useState<number | null>(null);

  const state = printPreviewState(job.data, render.isPending);
  const lines = renderLines(render.data);

  // A job this role may not read has nothing behind it — reprint history is
  // manager-gated (design.md §1: an API 403 renders as an access notice).
  if (isApiError(job.error) && job.error.status === 403) {
    return <AccessNotice screen="Print preview" />;
  }

  if (jobId === null) {
    return (
      <Shell>
        <p role="alert" data-testid="print-invalid" className="text-body text-accent-strong">
          That is not a print job number. Open a ticket from a settle, a shift close, or the job it
          belongs to.
        </p>
      </Shell>
    );
  }

  const startReprint = () => {
    setNotice(null);
    setReprintedAs(null);
    setReason(null);
    setReprinting(true);
  };

  const confirmReprint = () => {
    // Belt and braces: the button is disabled without a reason, and the
    // request is refused here too, so no code path can reach the server's 400.
    if (!reason) return;
    setNotice(null);
    reprint.mutate(
      { jobId, reason },
      {
        onSuccess: (copy) => {
          setReprinting(false);
          setReason(null);
          setReprintedAs(copy.id ?? null);
        },
        onError: (error) =>
          setNotice(
            hasErrorCode(error, 'FORBIDDEN')
              ? 'Reprinting another operator’s ticket needs a manager.'
              : errorNotice(error, 'That ticket could not be reprinted.'),
          ),
      },
    );
  };

  const fireRetry = () => {
    setNotice(null);
    setReprintedAs(null);
    retry.mutate(
      { jobId },
      {
        onError: (error) =>
          setNotice(errorNotice(error, 'That ticket could not be sent to the printer again.')),
      },
    );
  };

  return (
    <Shell>
      {job.isError ? (
        <p role="alert" data-testid="print-job-error" className="text-body text-accent-strong">
          {errorNotice(job.error, 'That print job could not be read.')}
        </p>
      ) : null}

      {notice ? (
        <p
          role="alert"
          data-testid="print-notice"
          className="border-2 border-accent px-3 py-2 text-body text-accent-strong"
        >
          {notice}
        </p>
      ) : null}

      {reprintedAs !== null ? (
        <p
          role="status"
          data-testid="print-reprinted"
          className="border-2 border-divider px-3 py-2 text-body"
        >
          Reprinted as job #{reprintedAs}.{' '}
          <Link
            href={{ pathname: `/print/${reprintedAs}` }}
            className="text-accent-strong underline underline-offset-4"
          >
            Open the copy
          </Link>
          .
        </p>
      ) : null}

      <div className="flex flex-wrap items-start gap-6">
        {/* -------------------------------------------------------- the paper */}
        <div className="flex min-w-[380px] flex-1 flex-col gap-2">
          <h2 className="type-label opacity-55">80 mm thermal ticket</h2>

          {state === 'rendering' ? (
            render.isError ? (
              <p role="alert" data-testid="print-render-error" className="text-body text-accent-strong">
                {errorNotice(render.error, 'The stored render could not be read.')}
              </p>
            ) : (
              <div
                data-testid="print-skeleton"
                aria-busy="true"
                className="flex flex-col gap-1.5 bg-paper p-4"
              >
                {[0, 1, 2, 3, 4, 5, 6, 7].map((row) => (
                  <div key={row} className="h-2.5 bg-track" style={{ width: `${92 - row * 6}%` }} />
                ))}
              </div>
            )
          ) : (
            <pre
              data-testid="print-render"
              data-columns={render.data?.columns ?? 48}
              className="tabular overflow-x-auto bg-paper px-3.5 py-4 type-mono text-[#201e1d] shadow-md"
            >
              {lines.join('\n')}
            </pre>
          )}
        </div>

        {/* -------------------------------------------------------- the rail */}
        <div
          data-testid="print-rail"
          data-state={state}
          className="flex w-[320px] flex-none flex-col gap-3.5 border-2 border-text p-4"
        >
          <JobFacts job={job.data} state={state} />

          {state === 'failed' ? (
            <div className="flex flex-col gap-2">
              <p
                role="alert"
                data-testid="print-failure"
                className="flex items-start gap-2 border-2 border-accent px-3 py-2 text-body text-accent-strong"
              >
                <AlertTriangle aria-hidden="true" className="mt-0.5 size-4 shrink-0" strokeWidth={2} />
                {printJobStatusNote(job.data)}
              </p>
              <Button
                variant="primary"
                loading={retry.isPending}
                onClick={fireRetry}
                data-testid="print-retry"
              >
                <RotateCcw aria-hidden="true" className="size-4" strokeWidth={2} />
                Retry this ticket
              </Button>
              <p className="text-[12px] opacity-60">
                The same bytes go back to the printer — fix the paper or the cable first, then
                retry. The attempt count keeps climbing.
              </p>
            </div>
          ) : (
            <p data-testid="print-status-note" className="text-[12px] opacity-60">
              {printJobStatusNote(job.data)}
            </p>
          )}

          {reprinting ? (
            <div data-testid="reprint-form" className="flex flex-col gap-2 border-t-2 border-divider pt-3">
              <p className="font-heading text-[13px] font-extrabold">Why is it being reprinted?</p>
              <ChipSelect<ReprintReason>
                label="Reprint reason"
                value={reason}
                onChange={setReason}
                options={REPRINT_REASONS.map((value) => ({
                  value,
                  label: REPRINT_REASON_LABELS[value],
                }))}
              />
              <p data-testid="reprint-reason-required" className="text-[12px] opacity-60">
                A reason is required — it prints on the band and stays on the job.
              </p>
              <div className="flex gap-2">
                <Button
                  variant="primary"
                  disabled={reason === null}
                  loading={reprint.isPending}
                  onClick={confirmReprint}
                  data-testid="reprint-confirm"
                >
                  Reprint ticket
                </Button>
                <Button variant="secondary" onClick={() => setReprinting(false)}>
                  Cancel
                </Button>
              </div>
            </div>
          ) : (
            <Button
              variant="secondary"
              onClick={startReprint}
              disabled={job.isPending || job.isError}
              data-testid="print-reprint"
            >
              <PrinterIcon aria-hidden="true" className="size-4" strokeWidth={2} />
              Reprint…
            </Button>
          )}
        </div>
      </div>
    </Shell>
  );
}

function Shell({ children }: { children: React.ReactNode }) {
  return (
    <section data-testid="print-preview-screen" className="flex flex-col gap-4 p-8">
      <header className="flex flex-col gap-1">
        <p className="type-label text-accent-strong">S11</p>
        <h1 className="text-h2">Print preview</h1>
        <p className="text-body opacity-70">
          The stored render, exactly as it was sent to the printer.
        </p>
      </header>
      {children}
    </section>
  );
}

/** Job id, type, status, device and — on a banded copy — what it is a copy of. */
function JobFacts({ job, state }: { job: PrintJob | undefined; state: string }) {
  if (!job) {
    return (
      <div data-testid="print-facts-skeleton" aria-busy="true" className="flex flex-col gap-2">
        <div className="h-3 w-24 bg-track" />
        <div className="h-3 w-32 bg-track" />
      </div>
    );
  }

  return (
    <div data-testid="print-facts" className="flex flex-col gap-2">
      <div className="flex items-center justify-between gap-2">
        <span className="tabular font-heading text-[15px] font-extrabold">Job #{job.id}</span>
        <Tag variant={state === 'failed' ? 'accent' : state === 'ready' ? 'neutral' : 'outline'}>
          {job.status ?? 'QUEUED'}
        </Tag>
      </div>

      <dl className="flex flex-col gap-1 text-[12px]">
        <Fact label="Document" value={printJobTypeLabel(job.type)} />
        {job.device ? <Fact label="Printer" value={job.device} /> : null}
        <Fact label="Attempts" value={String(job.attempts ?? 0)} />
      </dl>

      {job.isReprint ? (
        <p data-testid="reprint-band" className="border-2 border-divider px-2.5 py-2 text-[12px]">
          Reprint
          {job.reprintReason ? ` · ${REPRINT_REASON_LABELS[job.reprintReason as ReprintReason] ?? job.reprintReason}` : ''}
          {typeof job.originalJobId === 'number' ? (
            <>
              {' · of '}
              <Link
                href={{ pathname: `/print/${job.originalJobId}` }}
                className="text-accent-strong underline underline-offset-4"
              >
                job #{job.originalJobId}
              </Link>
            </>
          ) : null}
        </p>
      ) : null}
    </div>
  );
}

function Fact({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center justify-between gap-2">
      <dt className="opacity-55">{label}</dt>
      <dd className="tabular">{value}</dd>
    </div>
  );
}
