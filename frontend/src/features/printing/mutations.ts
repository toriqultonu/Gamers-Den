'use client';

/**
 * S11's two writes — `POST /print-jobs/{id}/retry` and
 * `POST /print-jobs/{id}/reprint` (api-contract.md, Print jobs).
 *
 * They are not the same action, and the screen must not let them blur:
 *
 *  - **retry** re-queues the *same row* with the *same bytes*. It exists for a
 *    ticket that never reached the customer — the printer was offline, out of
 *    paper, or stopped half-way — so nothing is re-rendered, nothing is
 *    recorded as a second document, and `attempts` keeps climbing. 409
 *    `CONFLICT` on a job that is not FAILED.
 *  - **reprint** is a second piece of paper, and is recorded as one: a new job
 *    carrying the original's stored bytes under the reprint band, with the
 *    reason attached and the original linked. The reason is required — the
 *    server answers 400 `VALIDATION_FAILED` without it, and this screen never
 *    lets it get that far. Reprinting someone else's ticket needs Manager+.
 *
 * Neither is optimistic (invariant §5.3: print jobs never are) and neither
 * carries an `Idempotency-Key` — the guarded list names `POST /print-jobs`,
 * not these subpaths, and a job whose state the server refuses to move is a
 * 409 rather than a double print.
 */

import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { queryKeys } from '@/lib/query-keys';
import type { PrintJob, ReprintReason } from './use-print-job';

/** `POST /print-jobs/{id}/retry` — FAILED only, same bytes, same row. */
export function useRetryPrintJob() {
  const client = useQueryClient();
  return useMutation<PrintJob, unknown, { jobId: number }>({
    mutationFn: ({ jobId }) => api.post<PrintJob>(`/print-jobs/${jobId}/retry`),
    onSuccess: (job, { jobId }) => {
      // The answer is the job itself, re-queued: write it where the poll reads
      // so the screen flips to "queued" on the same frame.
      client.setQueryData(queryKeys.printJobs.detail(jobId), job);
    },
  });
}

/** `POST /print-jobs/{id}/reprint` — a new banded job; the reason is required. */
export function useReprintJob() {
  const client = useQueryClient();
  return useMutation<PrintJob, unknown, { jobId: number; reason: ReprintReason }>({
    mutationFn: ({ jobId, reason }) =>
      api.post<PrintJob>(`/print-jobs/${jobId}/reprint`, { reason }),
    onSuccess: (job) => {
      // The new job is what the operator is about to open; seed its key so the
      // preview it lands on is not empty for a round trip. The render is a
      // separate read and stays uncached — it is the banded copy, not this one.
      if (typeof job.id === 'number') {
        client.setQueryData(queryKeys.printJobs.detail(job.id), job);
      }
    },
  });
}
