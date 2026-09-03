'use client';

/**
 * Print-job status and render, backed by `['print-jobs', id]`.
 *
 * Two reads with opposite lifetimes, which is why they are two queries:
 *
 *  - **the job** (`GET /print-jobs/{id}`) moves — QUEUED → PRINTING → DONE, or
 *    FAILED with the reason to fix (PAPER_OUT, COVER_OPEN, OFFLINE, MID_PRINT).
 *    It is polled while it is still moving and left alone once it stops. There
 *    is a `printer-status` SSE stream, but it carries printers, not jobs
 *    (lib/sse.ts), so the job itself is polled.
 *  - **the render** (`GET /print-jobs/{id}/render`) never moves. It is the
 *    stored 48-column text produced by the same pass that produced the bytes on
 *    the paper — "shows the stored render (never recomputed)" (design.md §5,
 *    S11), and invariant §5.6: no client-side receipt drawing, ever. Fetched
 *    once and cached forever.
 *
 * A job id is enough to render a receipt, which is the point: settle answers
 * with `printJobId` and the POS ticket column can draw the real paper without
 * knowing anything about what was sold.
 */

import { useQuery } from '@tanstack/react-query';
import { api, type Schemas } from '@/lib/api';
import { queryKeys } from '@/lib/query-keys';

export type PrintJob = Schemas['PrintJob'];
export type PrintRender = Schemas['PrintRender'];

/** `status` values the API answers with (api-contract.md, Print jobs). */
export const PRINT_JOB_STATUSES = ['QUEUED', 'PRINTING', 'DONE', 'FAILED'] as const;
export type PrintJobStatus = (typeof PRINT_JOB_STATUSES)[number];

/** How often a job that is still going is asked about. */
export const PRINT_JOB_POLL_MS = 1_500;

/** A job that has stopped moving: nothing left to poll for. */
export function isSettledJob(job: PrintJob | undefined): boolean {
  return job?.status === 'DONE' || job?.status === 'FAILED';
}

export function printJobQueryOptions(id: number) {
  return {
    queryKey: queryKeys.printJobs.detail(id),
    queryFn: () => api.get<PrintJob>(`/print-jobs/${id}`),
    // The job is the one thing on this screen that genuinely changes second to
    // second while the paper is moving.
    staleTime: 0,
  };
}

export function usePrintJob(id: number | null | undefined) {
  return useQuery({
    ...printJobQueryOptions(id ?? 0),
    enabled: typeof id === 'number' && id > 0,
    refetchInterval: (query) =>
      isSettledJob(query.state.data as PrintJob | undefined) ? false : PRINT_JOB_POLL_MS,
  });
}

export function printRenderQueryOptions(id: number) {
  return {
    queryKey: queryKeys.printJobs.render(id),
    queryFn: () => api.get<PrintRender>(`/print-jobs/${id}/render`),
    // Stored, not computed: it cannot change under us, so it is never refetched.
    staleTime: Number.POSITIVE_INFINITY,
    gcTime: 30 * 60_000,
  };
}

export function usePrintRender(id: number | null | undefined) {
  return useQuery({
    ...printRenderQueryOptions(id ?? 0),
    enabled: typeof id === 'number' && id > 0,
  });
}

/** The render's lines, ready for a character grid. Never re-wrapped. */
export function renderLines(render: PrintRender | undefined): string[] {
  const text = render?.text;
  if (typeof text !== 'string' || text === '') return [];
  return text.replace(/\r\n/g, '\n').split('\n');
}

/**
 * The house copy for a failed job — `error` names the thing to fix rather than
 * saying "try again" (design.md §5, S11 states).
 */
export const PRINT_FAILURES: Record<string, string> = {
  PAPER_OUT: 'The printer is out of paper.',
  COVER_OPEN: 'The printer cover is open.',
  OFFLINE: 'The printer is offline.',
  MID_PRINT: 'The printer stopped part-way through this ticket.',
  TRANSPORT: 'The terminal cannot reach the printer.',
};

export function printFailureNotice(job: PrintJob | undefined): string {
  const reason = job?.error;
  return (reason ? PRINT_FAILURES[reason] : undefined) ?? 'This ticket did not print.';
}
