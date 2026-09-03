'use client';

/**
 * Booking writes — create, check-in, cancel (docs/bookings.md §2).
 *
 * **None of them is optimistic** (frontend/ARCHITECTURE.md §5.3), and the three
 * reasons are different enough to be worth naming:
 *
 *  - **Create takes money.** The booking, its transaction, its tenders and the
 *    P1 + P7 print job are one server transaction, and the server — not this
 *    form — prices the slot. A row drawn ahead of the response would be a
 *    booking nobody has paid for, and its total would be a guess (§5.11).
 *  - **Check-in burns a token.** The next daily number comes off a row-locked
 *    counter; a badge shown before the response would be a promise about a
 *    queue position the server has not issued (§5.12).
 *  - **Cancel hands money back.** A refund drawn early and then refused is a
 *    customer told twice, in opposite directions.
 *
 * Create and cancel are on the guarded route list, so `lib/api.ts` attaches an
 * `Idempotency-Key` per intent (`features/bookings/schemas.ts`): a retry after
 * a timeout replays the stored answer rather than booking — or refunding —
 * twice. Check-in deliberately carries none: it takes no money and is already
 * idempotent through its own 409 `ALREADY_CHECKED_IN` (backend
 * `BookingController`).
 *
 * Every write ends by invalidating **both** tabs and the open booking, because
 * which tab a booking belongs to is the server's call: a check-in moves the row
 * from Upcoming to History, and a cancel does too.
 */

import { useMutation, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { api, type Schemas } from '@/lib/api';
import { queryKeys } from '@/lib/query-keys';
import {
  bookingIntent,
  cancelIntent,
  type BookingSettings,
  type CreateBookingInput,
  type UpdateBookingSettingsInput,
} from './schemas';

export type BookingCreated = Schemas['BookingCreated'];
export type BookingCheckedIn = Schemas['BookingCheckedIn'];
export type BookingCancelled = Schemas['BookingCancelled'];

/**
 * Everything a booking write moves.
 *
 * Both tabs, because the row may have crossed between them; the booking itself;
 * the shift, because the takings did; and `['stations']`, because a checked-in
 * arrival grows a seat prompt on the Floor card the moment it exists
 * (docs/bookings.md §2).
 */
function invalidateBooking(client: QueryClient, bookingId: number | undefined): void {
  void client.invalidateQueries({ queryKey: queryKeys.bookings.tab('upcoming') });
  void client.invalidateQueries({ queryKey: queryKeys.bookings.tab('history') });
  if (typeof bookingId === 'number') {
    void client.invalidateQueries({ queryKey: queryKeys.bookings.detail(bookingId) });
  }
  void client.invalidateQueries({ queryKey: queryKeys.shift.current() });
}

/* --------------------------------------------------------------- create */

/**
 * `POST /bookings` — pay first, then the slot is held.
 *
 * 409 `PREBOOKING_DISABLED` when the feature is off, `PAYMENT_REF_REQUIRED` on
 * an MFS payment with no TrxID, `WALLET_INSUFFICIENT` past the balance; each
 * leaves nothing written, and the form keeps every field the operator typed
 * (§4.4). An overlap on the same console is *not* a refusal — it comes back on
 * `overlappingBookingIds` as a warning (docs/bookings.md §7).
 */
export function useCreateBooking() {
  const client = useQueryClient();

  return useMutation<BookingCreated, unknown, CreateBookingInput>({
    mutationFn: (input) =>
      api.post<BookingCreated>(
        '/bookings',
        {
          stationId: input.stationId,
          memberId: input.memberId,
          name: input.name,
          phone: input.phone?.trim() || undefined,
          startAt: input.startAt,
          blocks: input.blocks,
          method: input.method,
          paymentRef: input.paymentRef?.trim() || undefined,
        },
        { intent: bookingIntent(input) },
      ),

    onSuccess: (created, input) => {
      invalidateBooking(client, created.booking?.id);
      // The member's wallet may have funded it, and their bookings strip grew.
      if (typeof input.memberId === 'number') {
        void client.invalidateQueries({ queryKey: queryKeys.members.detail(input.memberId) });
      }
    },
  });
}

/* -------------------------------------------------------------- check-in */

/**
 * `POST /bookings/{id}/check-in` — the next daily token, the P6 stub, ARRIVED.
 *
 * The queue gains a WAITING entry in the same transaction, so the Floor rail is
 * invalidated alongside the tabs: the customer is now someone who plays next.
 */
export function useCheckInBooking() {
  const client = useQueryClient();

  return useMutation<BookingCheckedIn, unknown, { bookingId: number }>({
    mutationFn: ({ bookingId }) =>
      api.post<BookingCheckedIn>(`/bookings/${bookingId}/check-in`, undefined),

    onSuccess: (checkedIn, { bookingId }) => {
      invalidateBooking(client, checkedIn.booking?.id ?? bookingId);
      void client.invalidateQueries({ queryKey: queryKeys.queue.all() });
      void client.invalidateQueries({ queryKey: queryKeys.stations.all() });
    },
  });
}

/* ---------------------------------------------------------------- cancel */

/**
 * `POST /bookings/{id}/cancel` — full refund, outside the window only.
 *
 * 409 `CANCEL_CUTOFF_PASSED` inside it (the rail already shows the lock note;
 * this is the backstop for a cutoff that passed while the rail was open) and
 * `ALREADY_CHECKED_IN` once the customer has arrived, which is a Manager+ void
 * of the transaction instead (docs/bookings.md §7).
 */
export function useCancelBooking() {
  const client = useQueryClient();

  return useMutation<BookingCancelled, unknown, { bookingId: number; reason?: string }>({
    mutationFn: ({ bookingId, reason }) =>
      api.post<BookingCancelled>(
        `/bookings/${bookingId}/cancel`,
        { reason: reason?.trim() || undefined },
        { intent: cancelIntent(bookingId) },
      ),

    onSuccess: (cancelled, { bookingId }) => {
      invalidateBooking(client, cancelled.booking?.id ?? bookingId);
      const memberId = cancelled.booking?.memberId;
      if (typeof memberId === 'number') {
        void client.invalidateQueries({ queryKey: queryKeys.members.detail(memberId) });
      }
    },
  });
}

/* -------------------------------------------------------------- settings */

/**
 * `PUT /booking-settings` (Admin, S10) — the feature flag, the package fee and
 * the cancellation window.
 *
 * The one write in this file that takes no money and still reaches the whole
 * venue: switching `enabled` off takes the Bookings nav item away from every
 * terminal and starts refusing new bookings with 409 `PREBOOKING_DISABLED`
 * (docs/bookings.md §1). So the answer is written straight into
 * `['booking-settings']` — the key the shell filters `NAV[role]` on
 * (frontend/ARCHITECTURE.md §4.3) — and the sidebar loses the item on the same
 * frame the switch settles, rather than up to a minute later when the flag
 * goes stale.
 *
 * Not optimistic, for the ordinary reason: a sidebar that dropped a screen and
 * then had to put it back on a 403 would be worse than one that waits a
 * round-trip. Fee and cutoff changes "apply to NEW bookings only" — every
 * booking already sold keeps the terms it was sold under, which is the
 * server's rule and needs nothing from the cache.
 */
export function useUpdateBookingSettings() {
  const client = useQueryClient();

  return useMutation<BookingSettings, unknown, UpdateBookingSettingsInput>({
    mutationFn: (settings) => api.put<BookingSettings>('/booking-settings', settings),
    onSuccess: (settings) => {
      client.setQueryData(queryKeys.bookings.settings(), settings);
    },
  });
}
