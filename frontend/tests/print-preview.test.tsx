/**
 * S11 — Print preview (design.md §5, "S11 Print preview"; frontend/
 * ARCHITECTURE.md §5.6).
 *
 * State-table assertions, not snapshots. The four rules this screen is not
 * allowed to get wrong:
 *
 *  - **the paper is the server's.** Every character on screen comes from
 *    `GET /print-jobs/{id}/render`, verbatim, including the reprint band —
 *    there is no client-side receipt drawing anywhere in this screen.
 *  - **the states are the design's:** rendering · ready · queued (printer
 *    offline) · failed (retry) · reprint-mode.
 *  - **a reprint without a reason never leaves the terminal.** The confirm is
 *    dead until one is picked, so the server's 400 is unreachable from here.
 *  - **retry is not reprint.** A FAILED job goes back to the printer as the
 *    same row with the same bytes; anything else is a new, banded job.
 */

import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PrintPreviewScreen } from '@/components/domain/print-preview-screen';
import { makeQueryClient } from '@/lib/query-client';
import { forgetSession, resetIdempotencyKeys } from '@/lib/api';
import { resetServerTime } from '@/lib/time';
import {
  PRINT_PREVIEW_STATES,
  REPRINT_REASONS,
  printJobTypeLabel,
  printPreviewState,
  renderLines,
} from '@/features/printing/use-print-job';

const NOW = '2026-09-03T14:00:00Z';

vi.mock('next/navigation', () => ({
  useRouter: () => ({
    replace: vi.fn(),
    push: vi.fn(),
    prefetch: vi.fn(),
    refresh: vi.fn(),
    back: vi.fn(),
  }),
  usePathname: () => '/print/41',
  useSearchParams: () => new URLSearchParams(),
}));

/* ------------------------------------------------------------- fixtures */

const RECEIPT_TEXT = [
  '           GAMER’S DEN',
  '      Bogura · 01700-000000',
  '--------------------------------',
  'TXN 41        STATION Titan',
  'GAMING 2x30M             240',
  'TOTAL                    240',
  '--------------------------------',
  '        Thank you — play again',
].join('\n');

const JOB = {
  id: 41,
  type: 'RECEIPT',
  refId: 41,
  status: 'DONE',
  attempts: 1,
  device: 'usb-1',
  operatorId: 1,
  isReprint: false,
  createdAt: NOW,
};

const RENDER = { id: 41, type: 'RECEIPT', columns: 48, bytes: 612, text: RECEIPT_TEXT };

/* --------------------------------------------------------------- server */

const fetchMock = vi.fn();

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', Date: new Date(NOW).toUTCString() },
  });
}

type Handlers = {
  job?: () => Response;
  render?: () => Response;
  retry?: () => Response;
  reprint?: (body: Record<string, unknown>) => Response;
};

const calls: { method: string; path: string; body: Record<string, unknown> }[] = [];

/** The job as the mock keeps it: a retry moves it back to QUEUED. */
let job: Record<string, unknown> = { ...JOB };

function serve(handlers: Handlers = {}) {
  fetchMock.mockImplementation((input: RequestInfo, init?: RequestInit) => {
    const url = new URL(String(input));
    const path = url.pathname.replace('/api/v1', '');
    const method = (init?.method ?? 'GET').toUpperCase();
    const body = (init?.body ? JSON.parse(String(init.body)) : {}) as Record<string, unknown>;
    calls.push({ method, path, body });

    if (method === 'GET' && path === '/print-jobs/41') return handlers.job?.() ?? json(job);
    if (method === 'GET' && path === '/print-jobs/41/render') {
      return handlers.render?.() ?? json(RENDER);
    }
    if (method === 'POST' && path === '/print-jobs/41/retry') {
      if (handlers.retry) return handlers.retry();
      job = { ...job, status: 'QUEUED', attempts: 2, error: undefined };
      return json(job);
    }
    if (method === 'POST' && path === '/print-jobs/41/reprint') {
      if (handlers.reprint) return handlers.reprint(body);
      return json(
        {
          id: 42,
          type: job.type,
          status: 'QUEUED',
          attempts: 0,
          device: 'usb-1',
          isReprint: true,
          reprintReason: body.reason,
          originalJobId: 41,
        },
        201,
      );
    }
    // A banded copy, opened by its own id.
    if (method === 'GET' && path === '/print-jobs/42') {
      return json({
        id: 42,
        type: 'RECEIPT',
        status: 'QUEUED',
        attempts: 0,
        isReprint: true,
        reprintReason: 'CUSTOMER_COPY',
        originalJobId: 41,
      });
    }
    if (method === 'GET' && path === '/print-jobs/42/render') {
      return json({ ...RENDER, id: 42, text: `*** REPRINT ***\n${RECEIPT_TEXT}` });
    }

    return json({});
  });
}

let client: QueryClient;

function renderPreview(jobId: number | null) {
  client = makeQueryClient();
  client.setDefaultOptions({ queries: { retry: false } });
  return render(
    <QueryClientProvider client={client}>
      <PrintPreviewScreen jobId={jobId} />
    </QueryClientProvider>,
  );
}

async function openPreview(jobId: number | null = 41) {
  const user = userEvent.setup();
  renderPreview(jobId);
  await waitFor(() => expect(screen.getByTestId('print-preview-screen')).toBeInTheDocument());
  return user;
}

const requests = (method: string, path: string) =>
  calls.filter((call) => call.method === method && call.path === path);

const railState = () => screen.getByTestId('print-rail').getAttribute('data-state');

beforeEach(() => {
  calls.length = 0;
  job = { ...JOB };
  vi.stubGlobal('fetch', fetchMock);
  serve();
});

afterEach(() => {
  client?.clear();
  forgetSession();
  resetIdempotencyKeys();
  resetServerTime();
  vi.unstubAllGlobals();
  fetchMock.mockReset();
});

/* ------------------------------------------------------------ the states */

describe('the S11 state table', () => {
  it('is exactly design.md §5’s set, derived from the two reads', () => {
    expect(PRINT_PREVIEW_STATES).toEqual(['rendering', 'ready', 'queued', 'failed']);

    expect(printPreviewState(undefined, true)).toBe('rendering');
    expect(printPreviewState({ status: 'DONE' }, true)).toBe('rendering');
    expect(printPreviewState({ status: 'DONE' }, false)).toBe('ready');
    expect(printPreviewState({ status: 'QUEUED' }, false)).toBe('queued');
    expect(printPreviewState({ status: 'PRINTING' }, false)).toBe('queued');
    expect(printPreviewState({ status: 'FAILED' }, false)).toBe('failed');
  });

  it('names every artifact the venue prints', () => {
    expect(printJobTypeLabel('PLAY_TICKET')).toBe('Play ticket');
    expect(printJobTypeLabel('BOOKING_CONFIRMATION')).toBe('Booking confirmation');
    // A type from a newer backend stays readable rather than becoming blank.
    expect(printJobTypeLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });

  it('draws a skeleton until the stored render is in hand', async () => {
    let release!: () => void;
    fetchMock.mockImplementation((input: RequestInfo) => {
      const path = new URL(String(input)).pathname.replace('/api/v1', '');
      if (path === '/print-jobs/41/render') {
        return new Promise<Response>((resolve) => {
          release = () => resolve(json(RENDER));
        });
      }
      return json(job);
    });

    renderPreview(41);

    expect(await screen.findByTestId('print-skeleton')).toBeInTheDocument();
    expect(screen.queryByTestId('print-render')).not.toBeInTheDocument();
    release();
    expect(await screen.findByTestId('print-render')).toBeInTheDocument();
  });

  it('draws the server’s stored render, character for character', async () => {
    await openPreview();

    const paper = await screen.findByTestId('print-render');

    expect(paper.textContent).toBe(RECEIPT_TEXT);
    expect(paper).toHaveAttribute('data-columns', '48');
    expect(renderLines(RENDER)).toHaveLength(8);
    await waitFor(() => expect(railState()).toBe('ready'));
    // One read of the render, and it is the only source of the paper.
    expect(requests('GET', '/print-jobs/41/render')).toHaveLength(1);
  });

  it('says the printer has not taken a queued ticket yet', async () => {
    job = { ...JOB, status: 'QUEUED', attempts: 0 };
    await openPreview();

    await waitFor(() => expect(railState()).toBe('queued'));
    expect(screen.getByTestId('print-status-note')).toHaveTextContent(
      'it prints as soon as the printer answers',
    );
    expect(screen.queryByTestId('print-retry')).not.toBeInTheDocument();
  });

  it('names the thing to fix on a failed ticket', async () => {
    job = { ...JOB, status: 'FAILED', error: 'PAPER_OUT', attempts: 1 };
    await openPreview();

    await waitFor(() => expect(railState()).toBe('failed'));
    expect(screen.getByTestId('print-failure')).toHaveTextContent(
      'The printer is out of paper.',
    );
  });

  it('refuses a job id that is not one', async () => {
    await openPreview(null);

    expect(screen.getByTestId('print-invalid')).toBeInTheDocument();
    expect(requests('GET', '/print-jobs/41')).toHaveLength(0);
  });

  it('explains a job that cannot be read', async () => {
    serve({
      job: () => json({ error: { code: 'NOT_FOUND', message: 'Print job 41', traceId: 't-1' } }, 404),
    });
    await openPreview();

    expect(await screen.findByTestId('print-job-error')).toBeInTheDocument();
  });
});

/* ---------------------------------------------------------------- retry */

describe('a failed job', () => {
  beforeEach(() => {
    job = { ...JOB, status: 'FAILED', error: 'OFFLINE', attempts: 3 };
  });

  it('goes back to the printer as the same row, same bytes', async () => {
    const user = await openPreview();

    await waitFor(() => expect(railState()).toBe('failed'));
    await user.click(screen.getByTestId('print-retry'));

    await waitFor(() => expect(requests('POST', '/print-jobs/41/retry')).toHaveLength(1));
    // Re-queued, and the screen follows the job the server answered with.
    await waitFor(() => expect(railState()).toBe('queued'));
    expect(screen.queryByTestId('print-retry')).not.toBeInTheDocument();
    // Nothing was re-rendered: the paper is still the one stored read.
    expect(requests('GET', '/print-jobs/41/render')).toHaveLength(1);
  });

  it('keeps the ticket on screen when the re-queue is refused', async () => {
    serve({
      retry: () =>
        json({ error: { code: 'CONFLICT', message: 'Print job 41 is QUEUED', traceId: 't-4' } }, 409),
    });
    const user = await openPreview();

    await waitFor(() => expect(railState()).toBe('failed'));
    await user.click(screen.getByTestId('print-retry'));

    expect(await screen.findByTestId('print-notice')).toBeInTheDocument();
    expect(screen.getByTestId('print-render')).toHaveTextContent('GAMER');
  });
});

/* -------------------------------------------------------------- reprint */

describe('reprint-mode', () => {
  it('offers the four reasons the contract names', async () => {
    const user = await openPreview();

    await user.click(await screen.findByTestId('print-reprint'));

    const reasons = within(screen.getByRole('group', { name: 'Reprint reason' }))
      .getAllByRole('button')
      .map((button) => button.textContent);

    expect(REPRINT_REASONS).toEqual(['LOST', 'DAMAGED', 'CUSTOMER_COPY', 'DISPUTE']);
    expect(reasons).toEqual(['Lost', 'Damaged', 'Customer copy', 'Dispute']);
  });

  it('sends nothing until a reason is picked', async () => {
    const user = await openPreview();

    await user.click(await screen.findByTestId('print-reprint'));

    const confirm = screen.getByTestId('reprint-confirm');
    expect(confirm).toBeDisabled();
    expect(screen.getByTestId('reprint-reason-required')).toBeInTheDocument();

    await user.click(confirm);
    expect(requests('POST', '/print-jobs/41/reprint')).toHaveLength(0);

    await user.click(screen.getByRole('button', { name: 'Customer copy' }));
    expect(screen.getByTestId('reprint-confirm')).toBeEnabled();
  });

  it('sends the reason and points at the copy it made', async () => {
    const user = await openPreview();

    await user.click(await screen.findByTestId('print-reprint'));
    await user.click(screen.getByRole('button', { name: 'Customer copy' }));
    await user.click(screen.getByTestId('reprint-confirm'));

    await waitFor(() => expect(requests('POST', '/print-jobs/41/reprint')).toHaveLength(1));
    expect(requests('POST', '/print-jobs/41/reprint')[0]!.body).toEqual({
      reason: 'CUSTOMER_COPY',
    });

    const done = await screen.findByTestId('print-reprinted');
    expect(done).toHaveTextContent('Reprinted as job #42');
    expect(within(done).getByRole('link', { name: 'Open the copy' })).toHaveAttribute(
      'href',
      '/print/42',
    );
    // The mode closes; the original ticket is still the one on screen.
    expect(screen.queryByTestId('reprint-form')).not.toBeInTheDocument();
    expect(screen.getByTestId('print-render')).toHaveTextContent('TXN 41');
  });

  it('explains the refusal when the ticket is someone else’s', async () => {
    serve({
      reprint: () =>
        json(
          {
            error: {
              code: 'FORBIDDEN',
              message: 'Reprinting another operator’s ticket needs a manager',
              traceId: 't-7',
            },
          },
          403,
        ),
    });
    const user = await openPreview();

    await user.click(await screen.findByTestId('print-reprint'));
    await user.click(screen.getByRole('button', { name: 'Lost' }));
    await user.click(screen.getByTestId('reprint-confirm'));

    expect(await screen.findByTestId('print-notice')).toHaveTextContent('needs a manager');
    // The form stays open with the reason still chosen — nothing entered is lost.
    expect(screen.getByTestId('reprint-form')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Lost' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('shows a banded copy as a copy, linked to what it copies', async () => {
    await openPreview(42);

    const band = await screen.findByTestId('reprint-band');

    expect(band).toHaveTextContent('Reprint · Customer copy');
    expect(within(band).getByRole('link', { name: 'job #41' })).toHaveAttribute(
      'href',
      '/print/41',
    );
    // The band is on the paper too — the server rendered it, not this screen.
    expect(screen.getByTestId('print-render')).toHaveTextContent('*** REPRINT ***');
  });
});
