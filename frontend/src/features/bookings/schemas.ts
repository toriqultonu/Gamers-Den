/**
 * Pre-booking shapes — docs/bookings.md §1–2, docs/api-contract.md (Pre-bookings).
 *
 * The booking form is pay-first, so everything it can check before taking money
 * is checked here: a console, a name, a start time, at least one 30-minute
 * block, and a TrxID when the tender is bKash/Nagad.
 *
 * The bill box these feed is a **preview** (frontend/ARCHITECTURE.md §5.11) —
 * the server re-prices at confirm from its own pricing and settings snapshot.
 */

import { z } from 'zod';
import type { Schemas } from '@/lib/api';
import { paymentMethodSchema } from '@/features/payments/schemas';
import { bookingTotal, formatBDT, playAmount } from '@/lib/money';

/** `bookings.status` (DDL, docs/bookings.md §5). */
export const BOOKING_STATUSES = ['PAID', 'ARRIVED', 'USED', 'CANCELLED'] as const;
export type BookingStatus = (typeof BOOKING_STATUSES)[number];

/** The two tabs of S14 — `['bookings', tab]` in the cache. */
export const BOOKING_TABS = ['upcoming', 'history'] as const;
export const bookingTabSchema = z.enum(BOOKING_TABS);
export type BookingTab = (typeof BOOKING_TABS)[number];

/** Upcoming = PAID; History = everything that has already happened. */
export const UPCOMING_STATUSES: readonly BookingStatus[] = ['PAID'];
export const HISTORY_STATUSES: readonly BookingStatus[] = ['ARRIVED', 'USED', 'CANCELLED'];

export const bookingSettingsSchema = z.object({
  enabled: z.boolean(),
  packageFee: z.int().nonnegative(),
  cancelCutoffHours: z.int().nonnegative(),
  updatedAt: z.iso.datetime({ offset: true }).optional(),
  updatedBy: z.int().optional(),
});

export type BookingSettings = z.infer<typeof bookingSettingsSchema>;

/** Admin-only write (S10). `enabled: false` hides the nav item everywhere. */
export const updateBookingSettingsSchema = z.object({
  enabled: z.boolean(),
  packageFee: z.int().nonnegative().max(100_000),
  cancelCutoffHours: z.int().nonnegative().max(72),
});

/**
 * The S14 form. `blocks` is capped at the backend's 48 (24 hours) and floored
 * at 1 — the TimeStepper's −30 is disabled there rather than hidden.
 */
export const createBookingSchema = z
  .object({
    stationId: z.int().positive(),
    memberId: z.int().positive().optional(),
    name: z.string().trim().min(1, 'Enter a name for the booking.').max(80),
    phone: z.string().trim().max(32).optional(),
    startAt: z.iso.datetime({ offset: true }),
    blocks: z.int().min(1).max(48),
    method: paymentMethodSchema,
    paymentRef: z.string().trim().max(64).optional(),
  })
  .refine(
    (booking) =>
      (booking.method !== 'BKASH' && booking.method !== 'NAGAD') || Boolean(booking.paymentRef),
    { error: 'Enter the bKash/Nagad TrxID.', path: ['paymentRef'] },
  );

export type CreateBookingInput = z.infer<typeof createBookingSchema>;

/** The generated request/response shapes these must keep fitting. */
export type CreateBookingRequest = Schemas['CreateBookingRequest'];
export type Booking = Schemas['Booking'];

export const cancelBookingSchema = z.object({
  reason: z.string().trim().max(200).optional(),
});

/**
 * Whether "Cancel & refund" is live, decided the same way the server decides
 * `CANCEL_CUTOFF_PASSED`: only while PAID, and only at least `cutoffHours`
 * before the start. `now` comes from the server-offset clock — never
 * `Date.now()` — so a wrong terminal clock cannot unlock a refund.
 */
export function isCancellable(
  booking: Pick<Booking, 'status' | 'startAt' | 'cutoffHours'>,
  now: number,
): boolean {
  if (booking.status !== 'PAID') return false;
  if (!booking.startAt) return false;
  const cutoffMs = (booking.cutoffHours ?? 0) * 3600_000;
  // The boundary itself still cancels — `now <= startAt − cutoffHours`, exactly
  // as the server measures it (backend `BookingService.requireCancellable`).
  return now <= Date.parse(booking.startAt) - cutoffMs;
}

/* ---------------------------------------------------------- what a row says */

/** The row and the rail label for each lifecycle state (design.md §1, S14). */
export const BOOKING_STATUS_LABELS: Record<BookingStatus, string> = {
  PAID: 'Paid',
  ARRIVED: 'Checked in',
  USED: 'Played',
  CANCELLED: 'Cancelled',
};

/** The fuller sentence the rail prints under the customer's name. */
export const BOOKING_STATUS_DETAIL: Record<BookingStatus, string> = {
  PAID: 'Paid — not yet arrived',
  ARRIVED: 'Checked in — waiting for a console',
  USED: 'Played — time loaded',
  CANCELLED: 'Cancelled — fully refunded',
};

/** Which Tag variant carries a status (design.md §2). */
export function bookingStatusTag(status: string | undefined): 'accent' | 'neutral' | 'outline' {
  if (status === 'PAID' || status === 'ARRIVED') return 'accent';
  if (status === 'USED') return 'outline';
  return 'neutral';
}

/** The status cell: a checked-in booking shows the token it is holding. */
export function bookingStatusLabel(booking: Pick<Booking, 'status' | 'tokenNo'>): string {
  const status = (booking.status ?? 'PAID') as BookingStatus;
  if (status === 'ARRIVED' && typeof booking.tokenNo === 'number') {
    return `Token #${String(booking.tokenNo).padStart(2, '0')}`;
  }
  return BOOKING_STATUS_LABELS[status] ?? status;
}

/**
 * The second line of the Starts cell — what this booking is waiting on.
 *
 * `now` is the server-offset clock, never `Date.now()`: "in ~3 h" is a claim
 * about the venue's time, and the terminal's own may be wrong (§5.2).
 */
export function bookingStartNote(
  booking: Pick<Booking, 'status' | 'startAt'>,
  now: number,
): string {
  switch (booking.status) {
    case 'ARRIVED':
      return 'waiting — seat from Floor';
    case 'USED':
      return 'seated — time loaded';
    case 'CANCELLED':
      return 'fully refunded';
    default:
      return startsIn(booking.startAt, now);
  }
}

/** "in ~3 h" / "in ~40 min" / "starting now" / "started 20 min ago". */
export function startsIn(startAt: string | undefined, now: number): string {
  if (!startAt) return '';
  const minutes = Math.round((Date.parse(startAt) - now) / 60_000);
  if (Number.isNaN(minutes)) return '';
  if (Math.abs(minutes) < 5) return 'starting now';
  if (minutes < 0) return `started ${humanGap(-minutes)} ago`;
  return `in ~${humanGap(minutes)}`;
}

function humanGap(minutes: number): string {
  if (minutes < 60) return `${minutes} min`;
  const hours = Math.round(minutes / 60);
  if (hours < 48) return `${hours} h`;
  return `${Math.round(hours / 24)} days`;
}

/* --------------------------------------------------------- cancel & check-in */

/**
 * What the rail's cancel affordance is right now.
 *
 *  - `available` — PAID and still outside the window: the button is live;
 *  - `locked`    — PAID but inside it: the cutoff note replaces the button,
 *                  which is the UI half of 409 `CANCEL_CUTOFF_PASSED`;
 *  - `arrived`   — already checked in: that money goes back through a
 *                  Manager+ void of the transaction, not a cancel
 *                  (docs/bookings.md §7);
 *  - `none`      — played or already cancelled; there is nothing to hand back.
 *
 * The server's own `cancellable` is honoured when it says no — it knows about
 * refund state this screen does not — but the clock is re-read locally so the
 * button locks itself as the start time approaches, without waiting for a
 * refetch to tell it.
 */
export type CancelState = 'available' | 'locked' | 'arrived' | 'none';

export function cancelState(
  booking: Pick<Booking, 'status' | 'startAt' | 'cutoffHours' | 'cancellable'>,
  now: number,
): CancelState {
  if (booking.status === 'ARRIVED') return 'arrived';
  if (booking.status !== 'PAID') return 'none';
  if (booking.cancellable === false) return 'locked';
  return isCancellable(booking, now) ? 'available' : 'locked';
}

/** The note that stands where the button was (design.md §1, S14). */
export function cutoffNote(booking: Pick<Booking, 'cutoffHours'>): string {
  const hours = booking.cutoffHours ?? 0;
  return `Cancellation is locked — refunds close ${hours} h before the start time.`;
}

/** Check-in is the PAID booking's one move; a second tap earns ALREADY_CHECKED_IN. */
export function canCheckIn(booking: Pick<Booking, 'status'>): boolean {
  return booking.status === 'PAID';
}

/** The stub band under a checked-in booking: `Nexus · 2 h PREPAID` (P6). */
export function stubMeta(booking: Pick<Booking, 'stationName' | 'blocks'>): string {
  const blocks = booking.blocks ?? 0;
  const minutes = blocks * 30;
  const length = minutes % 60 === 0 ? `${minutes / 60} H` : `${minutes} MIN`;
  return `${booking.stationName ?? ''} · ${length} PREPAID`.trim();
}

/* -------------------------------------------------------------- the bill box */

/**
 * The form's live bill box — **a preview, not a price** (§5.11).
 *
 * Play time is blocks × the console's current block rate; the package fee comes
 * from `booking_settings`. The server re-prices at confirm from its own rate
 * card (for the *booked* time, so a morning slot is sold at the morning rate)
 * and its own settings, and that figure is the one that is charged.
 */
export type BookingBill = {
  blocks: number;
  blockPrice: number;
  play: number;
  packageFee: number;
  total: number;
  /** False when the rate card has not answered — the total is not yet real. */
  priced: boolean;
};

export function bookingBill(
  blocks: number,
  blockPrice: number,
  packageFee: number,
): BookingBill {
  return {
    blocks,
    blockPrice,
    play: playAmount(blocks, blockPrice),
    packageFee,
    total: bookingTotal(blocks, blockPrice, packageFee),
    priced: blockPrice > 0,
  };
}

/**
 * What the server actually charged, against what the box promised.
 *
 * `null` when they agree — the overwhelmingly common case, and the one that
 * needs no words. Otherwise the rail says so out loud with the server's figure:
 * a rate card or a package fee edited between the preview and the confirm is
 * not something to let past in silence (§5.11).
 */
export function totalDrift(preview: number, charged: number | undefined): number | null {
  if (typeof charged !== 'number') return null;
  return charged === preview ? null : charged;
}

export function driftNotice(preview: number, charged: number): string {
  return (
    `Priced at ${formatBDT(charged)} by the server, not the ${formatBDT(preview)} this form ` +
    `showed — ${formatBDT(charged)} was taken. The rate card or the package fee changed while ` +
    `the form was open.`
  );
}

/* ------------------------------------------------------------- the overlap */

/**
 * Bookings already holding this console over this window.
 *
 * docs/bookings.md §7: two bookings on one console at one time are **allowed
 * with a warning** — staff override, because the token can be seated on any
 * free console of the same type. So this returns the clashes for the form to
 * show; it never blocks the confirm. The server flags the same thing on the
 * created booking (`overlapping`) and on the Upcoming rows.
 */
export function overlappingBookings(
  rows: readonly Booking[],
  candidate: { stationId: number | null; startAt: string | null; blocks: number },
): Booking[] {
  if (candidate.stationId === null || !candidate.startAt) return [];
  const start = Date.parse(candidate.startAt);
  if (Number.isNaN(start)) return [];
  const end = start + candidate.blocks * 30 * 60_000;

  return rows.filter((row) => {
    if (row.stationId !== candidate.stationId) return false;
    if (row.status !== 'PAID' && row.status !== 'ARRIVED') return false;
    if (!row.startAt) return false;
    const rowStart = Date.parse(row.startAt);
    if (Number.isNaN(rowStart)) return false;
    const rowEnd = row.endAt
      ? Date.parse(row.endAt)
      : rowStart + (row.blocks ?? 0) * 30 * 60_000;
    return rowStart < end && start < rowEnd;
  });
}

/* ----------------------------------------------------------------- intents */

/**
 * The idempotency intent for a confirm.
 *
 * One *intent* is "this form, as it now reads" — so a retry after a timeout
 * reuses the key and the server replays the booking it already made, while an
 * operator who edits the length or the console before trying again is making a
 * different booking and gets a fresh key (`lib/api.ts`, idempotency).
 */
export function bookingIntent(input: {
  stationId: number;
  startAt: string;
  blocks: number;
  method: string;
}): string {
  return `booking-create:${input.stationId}:${input.startAt}:${input.blocks}:${input.method}`;
}

/** One intent per booking cancelled — a retry must not refund twice. */
export function cancelIntent(bookingId: number): string {
  return `booking-cancel:${bookingId}`;
}
