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
  return Date.parse(booking.startAt) - cutoffMs > now;
}
