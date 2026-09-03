/**
 * S14 — Bookings: the tabs, the table, the detail rail and the pay-first form
 * (design.md §1 S14 + §2 component rows, docs/bookings.md).
 *
 * State-table assertions, not snapshots. What is pinned here is the handful of
 * rules this desk is not allowed to get wrong:
 *
 *  - the two tabs are two server reads, not one list filtered twice;
 *  - the bill box is blocks × the console's rate + the package fee, and when
 *    the server charges something else the rail says so with the server's
 *    figure — never a silent charge (§5.11);
 *  - the cutoff lock note stands where the cancel button was, and a cutoff that
 *    passes server-side comes back as the same sentence;
 *  - check-in shows the token only once the server has issued it, and the stub
 *    it draws is the server's stored render;
 *  - no booking write is optimistic: a refused cancel leaves the row exactly as
 *    it was, and a refused confirm keeps every typed field;
 *  - both empty states, and the `PREBOOKING_DISABLED` notice.
 */

import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BookingsScreen, policyLine } from '@/components/domain/bookings-screen';
import { emptyMessage } from '@/components/domain/booking-table';
import { defaultStart } from '@/components/domain/booking-form';
import { makeQueryClient } from '@/lib/query-client';
import { forgetSession, resetIdempotencyKeys } from '@/lib/api';
import { resetPosStore, useAppStore } from '@/features/pos/bill-store';
import {
  instantFromVenueLocal,
  resetServerTime,
  venueLocalInput,
  venueOffset,
  venueOffsetMinutes,
} from '@/lib/time';
import {
  bookingBill,
  bookingStartNote,
  bookingStatusLabel,
  bookingStatusTag,
  cancelState,
  createBookingSchema,
  cutoffNote,
  driftNotice,
  isCancellable,
  overlappingBookings,
  stubMeta,
  totalDrift,
  type Booking,
  type BookingSettings,
} from '@/features/bookings/schemas';
import type { Station } from '@/features/sessions/queries';
import type { Pricing } from '@/features/pos/queries';

const NOW = '2026-09-03T12:00:00Z'; // 18:00 in Dhaka
const NOW_MS = Date.parse(NOW);

/* ------------------------------------------------------------- fixtures */

const SETTINGS: BookingSettings = { enabled: true, packageFee: 100, cancelCutoffHours: 2 };

const STATIONS: Station[] = [
  { id: 1, name: 'Nexus', consoleType: 'PS5', floorState: 'RUNNING', status: 'AVAILABLE' },
  { id: 2, name: 'Vortex', consoleType: 'PS5', floorState: 'FREE', status: 'AVAILABLE' },
  { id: 3, name: 'Titan', consoleType: 'PS4', floorState: 'FREE', status: 'AVAILABLE' },
];

const PRICING: Pricing[] = [
  { consoleType: 'PS5', perHalfHour: 150, perHour: 300, currentBlockPrice: 150 },
  { consoleType: 'PS4', perHalfHour: 100, perHour: 200, currentBlockPrice: 100 },
];

/** Paid, starting in five hours — comfortably outside the two-hour cutoff. */
const FAR: Booking = {
  id: 90,
  stationId: 1,
  stationName: 'Nexus',
  consoleType: 'PS5',
  name: 'Rakib Hossain',
  phone: '+8801711000111',
  startAt: '2026-09-03T17:00:00Z',
  endAt: '2026-09-03T19:00:00Z',
  blocks: 4,
  playAmount: 600,
  packageFee: 100,
  total: 700,
  cutoffHours: 2,
  cancellableUntil: '2026-09-03T15:00:00Z',
  cancellable: true,
  status: 'PAID',
  transactionId: 5001,
  overlapping: false,
};

/** Paid, starting in one hour — inside the cutoff, so the lock note stands. */
const SOON: Booking = {
  ...FAR,
  id: 91,
  stationId: 2,
  stationName: 'Vortex',
  name: 'Nusrat Jahan',
  phone: '+8801812000222',
  startAt: '2026-09-03T13:00:00Z',
  endAt: '2026-09-03T15:00:00Z',
  cancellableUntil: '2026-09-03T11:00:00Z',
  cancellable: false,
  transactionId: 5002,
};

const PLAYED: Booking = {
  ...FAR,
  id: 80,
  name: 'Imran Kabir',
  status: 'USED',
  startAt: '2026-09-02T15:00:00Z',
  endAt: '2026-09-02T17:00:00Z',
  cancellable: false,
  tokenNo: 7,
  tokenDate: '2026-09-02',
  queueEntryId: 300,
  transactionId: 4900,
};

/* --------------------------------------------------------------- server */

const fetchMock = vi.fn();

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', Date: new Date(NOW).toUTCString() },
  });
}

function conflict(code: string, message: string) {
  return json({ error: { code, message, traceId: 't-1' } }, 409);
}

type Handlers = {
  settings?: () => Response;
  upcoming?: () => Response;
  history?: () => Response;
  create?: (body: Record<string, unknown>) => Response;
  checkIn?: () => Response;
  cancel?: () => Response;
};

/** Bookings the server has taken during a test — the Upcoming read includes them. */
let taken: Booking[] = [];

const calls: {
  method: string;
  path: string;
  query: URLSearchParams;
  body: Record<string, unknown>;
  key: string | null;
}[] = [];

function serve(handlers: Handlers = {}) {
  fetchMock.mockImplementation((input: RequestInfo, init?: RequestInit) => {
    const url = new URL(String(input));
    const path = url.pathname.replace('/api/v1', '');
    const method = (init?.method ?? 'GET').toUpperCase();
    const body = init?.body ? (JSON.parse(String(init.body)) as Record<string, unknown>) : {};
    const key = new Headers(init?.headers).get('Idempotency-Key');
    calls.push({ method, path, query: url.searchParams, body, key });

    if (method === 'GET' && path === '/booking-settings') {
      return handlers.settings?.() ?? json(SETTINGS);
    }
    if (method === 'GET' && path === '/stations') return json(STATIONS);
    if (method === 'GET' && path === '/pricing') return json(PRICING);
    if (method === 'GET' && path === '/members') {
      return json({ content: [], page: 0, size: 50, totalElements: 0 });
    }

    if (method === 'GET' && path === '/bookings') {
      const tab = url.searchParams.get('tab');
      if (tab === 'history') return handlers.history?.() ?? json([PLAYED]);
      return handlers.upcoming?.() ?? json([FAR, SOON, ...taken]);
    }
    if (method === 'POST' && path === '/bookings') {
      if (handlers.create) return handlers.create(body);
      // The default sale is the form's own default — Nexus, two blocks: ৳300 play
      // time plus the ৳100 package fee, exactly what the bill box previewed.
      const booking: Booking = {
        ...FAR,
        id: 95,
        name: String(body.name ?? ''),
        blocks: 2,
        playAmount: 300,
        total: 400,
      };
      taken = [...taken, booking];
      return json({ booking, transactionId: 6001, printJobId: 700 }, 201);
    }
    if (method === 'POST' && path === '/bookings/90/check-in') {
      return (
        handlers.checkIn?.() ??
        json({
          booking: { ...FAR, status: 'ARRIVED', tokenNo: 4, tokenDate: '2026-09-03', queueEntryId: 310 },
          token: { queueEntryId: 310, tokenNo: 4, tokenDate: '2026-09-03' },
          printJobId: 701,
        })
      );
    }
    if (method === 'POST' && path === '/bookings/90/cancel') {
      return (
        handlers.cancel?.() ??
        json({
          booking: { ...FAR, status: 'CANCELLED', cancellable: false },
          refundAmount: 700,
          refundTransactionId: 6002,
        })
      );
    }
    if (method === 'GET' && path === '/print-jobs/701') {
      return json({ id: 701, type: 'PLAY_TICKET', status: 'DONE', attempts: 1 });
    }
    if (method === 'GET' && path === '/print-jobs/701/render') {
      return json({ columns: 48, text: 'PLAY TICKET — PREBOOKED\nTOKEN #04\n' });
    }

    return json({});
  });
}

let client: QueryClient;

function renderBookings() {
  client = makeQueryClient();
  client.setDefaultOptions({ queries: { retry: false } });
  return render(
    <QueryClientProvider client={client}>
      <BookingsScreen />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock);
  fetchMock.mockReset();
  calls.length = 0;
  taken = [];
  resetPosStore();
  forgetSession();
  resetServerTime();
  resetIdempotencyKeys();
  serve();
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

const tabCalls = () =>
  calls
    .filter((call) => call.method === 'GET' && call.path === '/bookings')
    .map((call) => call.query.get('tab'));

async function openScreen() {
  const user = userEvent.setup();
  renderBookings();
  await waitFor(() => expect(screen.getByText('Rakib Hossain')).toBeInTheDocument());
  return user;
}

async function openBooking(name = 'Rakib Hossain') {
  const user = await openScreen();
  await user.click(screen.getByText(name));
  await waitFor(() => expect(screen.getByTestId('booking-detail')).toBeInTheDocument());
  return user;
}

/* ----------------------------------------------------------------- pure */

describe('booking shapes', () => {
  it('prices the bill box as blocks × the console rate + the package fee', () => {
    const bill = bookingBill(4, 150, 100);
    expect(bill.play).toBe(600);
    expect(bill.packageFee).toBe(100);
    expect(bill.total).toBe(700);
    expect(bill.priced).toBe(true);

    // No rate card yet: the box shows the fee alone and admits it is not a price.
    expect(bookingBill(4, 0, 100)).toMatchObject({ play: 0, total: 100, priced: false });
  });

  it('treats a server total that differs from the preview as drift, and says so', () => {
    expect(totalDrift(700, 700)).toBeNull();
    expect(totalDrift(700, undefined)).toBeNull();
    expect(totalDrift(700, 800)).toBe(800);
    expect(driftNotice(700, 800)).toMatch(/৳800/);
    expect(driftNotice(700, 800)).toMatch(/৳700/);
  });

  it('locks the cancel inside the booking’s own cutoff, on the server clock', () => {
    expect(isCancellable(FAR, NOW_MS)).toBe(true);
    expect(isCancellable(SOON, NOW_MS)).toBe(false);
    expect(cancelState(FAR, NOW_MS)).toBe('available');
    expect(cancelState(SOON, NOW_MS)).toBe('locked');
    expect(cancelState({ ...FAR, status: 'ARRIVED' }, NOW_MS)).toBe('arrived');
    expect(cancelState(PLAYED, NOW_MS)).toBe('none');
    // The boundary itself still cancels (backend BookingService.requireCancellable).
    const boundary = Date.parse(FAR.cancellableUntil ?? '');
    expect(isCancellable(FAR, boundary)).toBe(true);
    expect(isCancellable(FAR, boundary + 1)).toBe(false);
    expect(cutoffNote(FAR)).toMatch(/2 h before the start/);
  });

  it('labels a row by what it is waiting on', () => {
    expect(bookingStatusLabel(FAR)).toBe('Paid');
    expect(bookingStatusLabel({ status: 'ARRIVED', tokenNo: 4 })).toBe('Token #04');
    expect(bookingStatusLabel(PLAYED)).toBe('Played');
    expect(bookingStatusTag('PAID')).toBe('accent');
    expect(bookingStatusTag('CANCELLED')).toBe('neutral');
    expect(bookingStartNote(FAR, NOW_MS)).toBe('in ~5 h');
    expect(bookingStartNote({ status: 'ARRIVED' }, NOW_MS)).toMatch(/seat from Floor/);
    expect(bookingStartNote(PLAYED, NOW_MS)).toMatch(/time loaded/);
    expect(stubMeta(FAR)).toBe('Nexus · 2 H PREPAID');
  });

  it('flags an overlapping slot on the same console — a warning, never a refusal', () => {
    const clash = overlappingBookings([FAR, SOON], {
      stationId: 1,
      startAt: '2026-09-03T18:00:00Z',
      blocks: 2,
    });
    expect(clash.map((row) => row.id)).toEqual([90]);

    // A different console, and the same console after the slot ends, both clear.
    expect(
      overlappingBookings([FAR], { stationId: 3, startAt: '2026-09-03T18:00:00Z', blocks: 2 }),
    ).toEqual([]);
    expect(
      overlappingBookings([FAR], { stationId: 1, startAt: '2026-09-03T19:00:00Z', blocks: 2 }),
    ).toEqual([]);
    // Cancelled bookings hold nothing.
    expect(
      overlappingBookings([{ ...FAR, status: 'CANCELLED' }], {
        stationId: 1,
        startAt: '2026-09-03T18:00:00Z',
        blocks: 2,
      }),
    ).toEqual([]);
  });

  it('takes a booking only with a console, a name, a future slot and an MFS ref', () => {
    const valid = {
      stationId: 1,
      name: 'Rakib',
      startAt: '2026-09-03T21:00:00+06:00',
      blocks: 2,
      method: 'CASH' as const,
    };
    expect(createBookingSchema.safeParse(valid).success).toBe(true);
    expect(createBookingSchema.safeParse({ ...valid, name: '  ' }).success).toBe(false);
    expect(createBookingSchema.safeParse({ ...valid, blocks: 0 }).success).toBe(false);
    expect(createBookingSchema.safeParse({ ...valid, method: 'BKASH' }).success).toBe(false);
    expect(
      createBookingSchema.safeParse({ ...valid, method: 'BKASH', paymentRef: 'TRX99' }).success,
    ).toBe(true);
  });

  it('reads and writes the picker in venue wall time, whatever the terminal is set to', () => {
    expect(venueOffsetMinutes(NOW_MS)).toBe(360);
    expect(venueOffset(NOW_MS)).toBe('+06:00');
    expect(venueLocalInput(NOW_MS)).toBe('2026-09-03T18:00');
    expect(instantFromVenueLocal('2026-09-03T21:00')).toBe('2026-09-03T21:00:00+06:00');
    expect(Date.parse(instantFromVenueLocal('2026-09-03T21:00') ?? '')).toBe(
      Date.parse('2026-09-03T15:00:00Z'),
    );
    expect(instantFromVenueLocal('not a time')).toBeNull();
    // The default slot is the next half hour, two hours out.
    expect(defaultStart(Date.parse('2026-09-03T12:07:00Z'))).toBe(
      Date.parse('2026-09-03T14:30:00Z'),
    );
  });

  it('spells the policy from the settings, never from a hard-coded fee', () => {
    expect(policyLine(SETTINGS)).toMatch(/৳100 package fee/);
    expect(policyLine(SETTINGS)).toMatch(/until 2 h before start/);
    expect(emptyMessage('upcoming')).toMatch(/New booking/);
    expect(emptyMessage('history')).toBe('No past bookings yet.');
  });
});

/* ---------------------------------------------------------------- tabs */

describe('S14 — the tabs', () => {
  it('asks the server for each tab and shows only that tab’s rows', async () => {
    const user = await openScreen();

    // The tab and the count are the same key, so they are one request.
    expect(tabCalls()).toEqual(['upcoming']);
    expect(screen.getByText('Nusrat Jahan')).toBeInTheDocument();
    expect(screen.queryByText('Imran Kabir')).not.toBeInTheDocument();
    expect(screen.getByTestId('bookings-tab-upcoming')).toHaveTextContent('Upcoming · 2');

    await user.click(screen.getByTestId('bookings-tab-history'));

    await waitFor(() => expect(screen.getByText('Imran Kabir')).toBeInTheDocument());
    expect(tabCalls()).toContain('history');
    expect(screen.queryByText('Rakib Hossain')).not.toBeInTheDocument();
    // The count is the Upcoming tab's, and it survives the switch.
    expect(screen.getByTestId('bookings-tab-upcoming')).toHaveTextContent('Upcoming · 2');
  });

  it('empties differently on each tab', async () => {
    serve({ upcoming: () => json([]), history: () => json([]) });
    const user = userEvent.setup();
    renderBookings();

    await waitFor(() => expect(screen.getByTestId('bookings-empty-upcoming')).toBeInTheDocument());
    expect(screen.getByTestId('bookings-empty-upcoming')).toHaveTextContent(
      'No upcoming bookings — take one with New booking.',
    );

    await user.click(screen.getByTestId('bookings-tab-history'));

    await waitFor(() => expect(screen.getByTestId('bookings-empty-history')).toBeInTheDocument());
    expect(screen.getByTestId('bookings-empty-history')).toHaveTextContent('No past bookings yet.');
  });

  it('switching tab drops the selection — that booking is not on this screen', async () => {
    const user = await openBooking();
    await user.click(screen.getByTestId('bookings-tab-history'));

    await waitFor(() => expect(screen.queryByTestId('booking-detail')).not.toBeInTheDocument());
    expect(screen.getByTestId('bookings-rail-idle')).toBeInTheDocument();
    expect(useAppStore.getState().selectedBookingId).toBeNull();
  });

  it('a failed read is a banner, and a 403 is the access notice', async () => {
    serve({ upcoming: () => json({ error: { code: 'SYNC_UNAVAILABLE', message: 'down' } }, 503) });
    renderBookings();
    await waitFor(() => expect(screen.getByTestId('bookings-error')).toBeInTheDocument());

    serve({ upcoming: () => json({ error: { code: 'FORBIDDEN', message: 'no' } }, 403) });
    renderBookings();
    await waitFor(() => expect(screen.getAllByTestId('access-notice').length).toBeGreaterThan(0));
  });
});

/* ---------------------------------------------------------------- rail */

describe('S14 — the detail rail', () => {
  it('opens on the booking with its console, slot, length and what was paid', async () => {
    await openBooking();

    const rail = screen.getByTestId('booking-detail');
    expect(within(rail).getByText('Rakib Hossain')).toBeInTheDocument();
    expect(within(rail).getByText('Nexus')).toBeInTheDocument();
    expect(within(rail).getByText('2 h')).toBeInTheDocument();
    expect(within(rail).getByText('৳700')).toBeInTheDocument();
    expect(within(rail).getByTestId('booking-status')).toHaveTextContent('Paid — not yet arrived');
  });

  it('renders the cutoff lock note instead of the cancel button inside the window', async () => {
    const user = await openScreen();
    await user.click(screen.getByText('Nusrat Jahan'));

    await waitFor(() => expect(screen.getByTestId('booking-detail')).toBeInTheDocument());
    expect(screen.getByTestId('booking-cutoff-note')).toHaveTextContent(
      /refunds close 2 h before the start time/,
    );
    expect(screen.queryByTestId('booking-cancel')).not.toBeInTheDocument();
    // Check-in is unaffected: the customer is at the door either way.
    expect(screen.getByTestId('booking-check-in')).toBeInTheDocument();
  });

  it('check-in shows the token only once the server has issued it, with the stored stub', async () => {
    const user = await openBooking();

    expect(screen.queryByTestId('booking-token')).not.toBeInTheDocument();

    await user.click(screen.getByTestId('booking-check-in'));

    await waitFor(() => expect(screen.getByTestId('booking-token')).toBeInTheDocument());
    expect(screen.getByTestId('booking-token')).toHaveTextContent('TOKEN #04');
    expect(screen.getByTestId('booking-token')).toHaveTextContent(/Nexus · 2 H PREPAID/);
    expect(screen.getByTestId('booking-token')).toHaveTextContent(/seat them from the floor/i);

    // The stub is the server's stored render, never drawn here (§5.6).
    await waitFor(() => expect(screen.getByTestId('receipt-render')).toBeInTheDocument());
    expect(screen.getByTestId('receipt-render')).toHaveTextContent('PLAY TICKET — PREBOOKED');
    expect(calls.some((call) => call.path === '/print-jobs/701/render')).toBe(true);

    // Check-in carries no idempotency key — it is idempotent through its own 409.
    const checkIn = calls.find((call) => call.path === '/bookings/90/check-in');
    expect(checkIn?.key).toBeNull();
  });

  it('a refused check-in is a notice, and no token is claimed', async () => {
    serve({ checkIn: () => conflict('ALREADY_CHECKED_IN', 'already in') });
    const user = await openBooking();

    await user.click(screen.getByTestId('booking-check-in'));

    await waitFor(() =>
      expect(screen.getByTestId('booking-detail-notice')).toHaveTextContent(
        /already been checked in/i,
      ),
    );
    expect(screen.queryByTestId('booking-token')).not.toBeInTheDocument();
  });

  it('cancel-refund is never optimistic: a refusal leaves the row exactly as it was', async () => {
    serve({ cancel: () => conflict('CANCEL_CUTOFF_PASSED', 'too late') });
    const user = await openBooking();

    await user.click(screen.getByTestId('booking-cancel'));

    await waitFor(() =>
      expect(screen.getByTestId('booking-detail-notice')).toHaveTextContent(
        /refunds close 2 h before the start time/,
      ),
    );
    // The booking is still PAID on the row, in the rail and in the cache — nothing
    // was written ahead of the server.
    expect(screen.getByTestId('booking-detail')).toHaveAttribute('data-status', 'PAID');
    expect(screen.getByTestId('booking-status')).toHaveTextContent('Paid — not yet arrived');
    expect(client.getQueryData(['bookings', 'upcoming'])).toEqual([FAR, SOON]);
  });

  it('a successful cancel carries one idempotency key and re-reads both tabs', async () => {
    // History is read once first, so there is a cached tab for the cancel to
    // mark stale — a cancelled booking belongs to it now.
    const user = await openScreen();
    await user.click(screen.getByTestId('bookings-tab-history'));
    await waitFor(() => expect(screen.getByText('Imran Kabir')).toBeInTheDocument());
    await user.click(screen.getByTestId('bookings-tab-upcoming'));
    await waitFor(() => expect(screen.getByText('Rakib Hossain')).toBeInTheDocument());
    await user.click(screen.getByText('Rakib Hossain'));
    await waitFor(() => expect(screen.getByTestId('booking-detail')).toBeInTheDocument());

    await user.click(screen.getByTestId('booking-cancel'));

    await waitFor(() =>
      expect(calls.some((call) => call.path === '/bookings/90/cancel')).toBe(true),
    );
    const cancel = calls.find((call) => call.path === '/bookings/90/cancel');
    expect(cancel?.key).toMatch(/[0-9a-f-]{8,}/);

    // Both tabs are marked stale — Upcoming is on screen so it re-reads at once,
    // History is invalidated and re-reads when the operator switches to it.
    await waitFor(() =>
      expect(tabCalls().filter((tab) => tab === 'upcoming').length).toBeGreaterThan(2),
    );
    expect(client.getQueryState(['bookings', 'history'])?.isInvalidated).toBe(true);
  });

  it('an arrived booking offers no cancel — that refund is a manager void', async () => {
    serve({ upcoming: () => json([{ ...FAR, status: 'ARRIVED', tokenNo: 4, tokenDate: '2026-09-03' }]) });
    await openBooking();

    expect(screen.queryByTestId('booking-cancel')).not.toBeInTheDocument();
    expect(screen.queryByTestId('booking-check-in')).not.toBeInTheDocument();
    expect(screen.getByTestId('booking-arrived-note')).toBeInTheDocument();
    // The token it already holds is shown without a fresh check-in.
    expect(screen.getByTestId('booking-token')).toHaveTextContent('TOKEN #04');
  });
});

/* ---------------------------------------------------------------- form */

async function openForm() {
  const user = await openScreen();
  await user.click(screen.getByTestId('new-booking'));
  await waitFor(() => expect(screen.getByTestId('booking-form')).toBeInTheDocument());
  return user;
}

describe('S14 — the pay-first form', () => {
  it('adds up play time at the console’s rate plus the package fee, live', async () => {
    const user = await openForm();

    // Nexus (PS5, ৳150 a block) at the default two blocks: 300 + 100.
    await waitFor(() => expect(screen.getByTestId('booking-total')).toHaveTextContent('৳400'));

    await user.click(screen.getByRole('button', { name: 'Add 30 minutes' }));
    expect(screen.getByTestId('booking-total')).toHaveTextContent('৳550');

    // A PS4 console at ৳100 a block re-prices the same three blocks.
    await user.click(screen.getByRole('button', { name: 'Titan · PS4' }));
    await waitFor(() => expect(screen.getByTestId('booking-total')).toHaveTextContent('৳400'));

    const box = screen.getByTestId('booking-bill-box');
    expect(within(box).getByText('৳300')).toBeInTheDocument(); // play time
    expect(within(box).getByText('৳100')).toBeInTheDocument(); // package fee
    expect(screen.getByTestId('booking-confirm')).toHaveTextContent('Take ৳400 & confirm booking');
  });

  it('confirms with the venue-time slot and keeps the form when the server refuses', async () => {
    serve({ create: () => conflict('PAYMENT_REF_REQUIRED', 'trxid missing') });
    const user = await openForm();

    await user.type(screen.getByLabelText('Customer name'), 'Rakib Hossain');
    await user.clear(screen.getByTestId('booking-start'));
    await user.type(screen.getByTestId('booking-start'), '2026-09-04T21:00');
    await user.click(screen.getByRole('button', { name: 'bKash' }));
    await user.type(screen.getByLabelText('TrxID'), 'TRX77');
    await user.click(screen.getByTestId('booking-confirm'));

    await waitFor(() =>
      expect(screen.getByTestId('booking-form-notice')).toHaveTextContent(/TrxID/i),
    );

    const create = calls.find((call) => call.method === 'POST' && call.path === '/bookings');
    expect(create?.body).toMatchObject({
      stationId: 1,
      name: 'Rakib Hossain',
      startAt: '2026-09-04T21:00:00+06:00',
      blocks: 2,
      method: 'BKASH',
      paymentRef: 'TRX77',
    });
    expect(create?.key).toMatch(/[0-9a-f-]{8,}/);

    // Nothing typed is lost, and the rail is still the form (§4.4).
    expect(screen.getByTestId('booking-form')).toBeInTheDocument();
    expect(screen.getByLabelText('Customer name')).toHaveValue('Rakib Hossain');
    expect(screen.getByLabelText('TrxID')).toHaveValue('TRX77');
  });

  it('says so when the server charges something other than the box promised', async () => {
    serve({
      create: () =>
        json({ booking: { ...FAR, id: 95, total: 800 }, transactionId: 6001, printJobId: 700 }, 201),
    });
    const user = await openForm();

    await user.type(screen.getByLabelText('Customer name'), 'Rakib Hossain');
    await user.click(screen.getByTestId('booking-confirm'));

    await waitFor(() => expect(screen.getByTestId('booking-drift-notice')).toBeInTheDocument());
    expect(screen.getByTestId('booking-drift-notice')).toHaveTextContent(/৳800/);
    expect(screen.getByTestId('booking-drift-notice')).toHaveTextContent(/৳400/);
  });

  it('a booking that priced as promised lands with no notice and opens in the rail', async () => {
    const user = await openForm();

    await user.type(screen.getByLabelText('Customer name'), 'Rakib Hossain');
    await user.click(screen.getByTestId('booking-confirm'));

    await waitFor(() => expect(screen.getByTestId('booking-detail')).toBeInTheDocument());
    expect(screen.queryByTestId('booking-drift-notice')).not.toBeInTheDocument();
    expect(useAppStore.getState().selectedBookingId).toBe(95);
    expect(useAppStore.getState().bookingsTab).toBe('upcoming');
  });

  it('refuses to send an empty form, and points at the field', async () => {
    const user = await openForm();

    await user.click(screen.getByTestId('booking-confirm'));

    await waitFor(() =>
      expect(screen.getByText('Enter a name for the booking.')).toBeInTheDocument(),
    );
    expect(calls.some((call) => call.method === 'POST' && call.path === '/bookings')).toBe(false);
  });

  it('warns about a double-booked console without blocking the sale', async () => {
    const user = await openForm();

    // FAR holds Nexus 17:00–19:00Z, i.e. 23:00–01:00 venue time.
    await user.clear(screen.getByTestId('booking-start'));
    await user.type(screen.getByTestId('booking-start'), '2026-09-03T23:30');

    await waitFor(() => expect(screen.getByTestId('booking-overlap-warning')).toBeInTheDocument());
    expect(screen.getByTestId('booking-overlap-warning')).toHaveTextContent(/Rakib Hossain/);
    expect(screen.getByTestId('booking-confirm')).toBeEnabled();
  });
});

/* ------------------------------------------------------- the feature flag */

describe('S14 — pre-booking switched off', () => {
  it('says so, refuses new bookings and keeps the paid ones serviceable', async () => {
    serve({ settings: () => json({ ...SETTINGS, enabled: false }) });
    const user = userEvent.setup();
    renderBookings();

    await waitFor(() => expect(screen.getByTestId('prebooking-disabled')).toBeInTheDocument());
    expect(screen.getByTestId('prebooking-disabled')).toHaveTextContent(/switched off in Setup/);
    expect(screen.getByTestId('new-booking')).toBeDisabled();

    // The bookings already paid for still check in (docs/bookings.md §7).
    await user.click(screen.getByText('Rakib Hossain'));
    await waitFor(() => expect(screen.getByTestId('booking-check-in')).toBeEnabled());
  });

  it('renders 409 PREBOOKING_DISABLED on the form as its own notice', async () => {
    serve({ create: () => conflict('PREBOOKING_DISABLED', 'off') });
    const user = await openForm();

    await user.type(screen.getByLabelText('Customer name'), 'Rakib Hossain');
    await user.click(screen.getByTestId('booking-confirm'));

    await waitFor(() =>
      expect(screen.getByTestId('booking-form-notice')).toHaveTextContent(
        'Pre-booking is switched off in Setup.',
      ),
    );
    expect(screen.getByTestId('booking-form')).toBeInTheDocument();
  });
});

/* --------------------------------------------------------- the rail state */

describe('S14 — the rail is one thing at a time', () => {
  it('opening the form drops the selection, and picking a row closes the form', async () => {
    const user = await openBooking();
    expect(screen.queryByTestId('booking-form')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Close' }));
    await user.click(screen.getByTestId('new-booking'));

    await waitFor(() => expect(screen.getByTestId('booking-form')).toBeInTheDocument());
    expect(screen.queryByTestId('booking-detail')).not.toBeInTheDocument();

    await act(async () => {
      useAppStore.getState().selectBooking(90);
    });

    await waitFor(() => expect(screen.getByTestId('booking-detail')).toBeInTheDocument());
    expect(screen.queryByTestId('booking-form')).not.toBeInTheDocument();
  });
});
