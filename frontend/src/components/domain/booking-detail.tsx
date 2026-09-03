'use client';

/**
 * BookingDetail — docs/design.md §2: variants "paid, arrived (token + stub),
 * played, cancelled"; states "check-in, cancel, cutoff-locked note"; prop
 * `booking`.
 *
 * The rail is one booking and the two things that can still be done to it.
 *
 * **Check in & print token** assigns the next daily queue number, prints the P6
 * stub and moves the row to History. It is never optimistic: the token comes
 * off a row-locked counter server-side, and a badge drawn ahead of the response
 * would be a promise about a queue position nobody has issued. When the
 * response lands, the token is shown large — the operator is about to say it
 * out loud — above the paper as the printer produced it (`ReceiptPreview`
 * draws the server's stored render; §5.6 forbids drawing one here).
 *
 * **Cancel & refund** is live only outside the booking's own cutoff window. The
 * lock is computed from the booking's `cutoffHours` snapshot against the
 * server-offset clock, so it closes itself while the rail sits open, and the
 * note that replaces the button says why. If the cutoff passes between the
 * click and the write, 409 `CANCEL_CUTOFF_PASSED` says the same thing in the
 * notice — the UI lock and the server rule are the same rule twice.
 */

import { useState } from 'react';
import { AlertTriangle, CalendarClock } from 'lucide-react';
import { ReceiptPreview } from './receipt-preview';
import { Button } from '@/components/ui/button';
import { Tag } from '@/components/ui/tag';
import { TokenBadge } from '@/components/ui/token-badge';
import { formatBlocks } from '@/components/ui/time-stepper';
import { errorNotice, hasErrorCode } from '@/lib/api';
import { formatBDT } from '@/lib/money';
import { formatVenueDateTime, serverNow, venueToday } from '@/lib/time';
import { useCancelBooking, useCheckInBooking } from '@/features/bookings/mutations';
import {
  BOOKING_STATUS_DETAIL,
  bookingStatusTag,
  canCheckIn,
  cancelState,
  cutoffNote,
  startsIn,
  stubMeta,
  type Booking,
  type BookingStatus,
} from '@/features/bookings/schemas';

export type BookingDetailProps = {
  booking: Booking;
  onClose: () => void;
  /**
   * The check-in went through, with the booking the server answered with.
   *
   * Check-in moves a booking from Upcoming to History (docs/bookings.md §2), so
   * without this the list re-read pulls the row — and this rail with it — out
   * from under the token the operator is about to call out. The screen follows
   * the row instead, and holds on to this copy while the lists catch up.
   */
  onCheckedIn?: (booking: Booking) => void;
  /** Server-offset now, injected so the cutoff maths stays testable. */
  now?: number;
  today?: string;
};

export function BookingDetail({ booking, onClose, onCheckedIn, now, today }: BookingDetailProps) {
  const at = now ?? serverNow();
  const day = today ?? venueToday(at);

  const checkIn = useCheckInBooking();
  const cancel = useCancelBooking();

  /** The token this rail just issued — with the paper that came with it. */
  const [issued, setIssued] = useState<{ tokenNo: number; tokenDate?: string; printJobId: number | null } | null>(
    null,
  );
  const [notice, setNotice] = useState<string | null>(null);

  const status = (booking.status ?? 'PAID') as BookingStatus;
  const cancelling = cancelState(booking, at);
  const busy = checkIn.isPending || cancel.isPending;

  // A booking already checked in carries its token on the row; one checked in
  // just now also carries its print job, so the stub can be shown.
  const token =
    issued ??
    (typeof booking.tokenNo === 'number'
      ? { tokenNo: booking.tokenNo, tokenDate: booking.tokenDate, printJobId: null }
      : null);

  const onCheckIn = () => {
    if (busy || typeof booking.id !== 'number') return;
    setNotice(null);
    checkIn.mutate(
      { bookingId: booking.id },
      {
        onSuccess: (result) => {
          setIssued({
            tokenNo: result.token?.tokenNo ?? 0,
            tokenDate: result.token?.tokenDate,
            printJobId: result.printJobId ?? null,
          });
          onCheckedIn?.(
            result.booking ?? {
              ...booking,
              status: 'ARRIVED',
              tokenNo: result.token?.tokenNo,
              tokenDate: result.token?.tokenDate,
            },
          );
        },
        // `ALREADY_CHECKED_IN` — another terminal got there first. The booking
        // is fine; the refetch behind this notice brings its token in.
        onError: (error) => setNotice(errorNotice(error, 'The check-in did not go through.')),
      },
    );
  };

  const onCancel = () => {
    if (busy || typeof booking.id !== 'number') return;
    setNotice(null);
    cancel.mutate(
      { bookingId: booking.id },
      {
        onError: (error) =>
          setNotice(
            hasErrorCode(error, 'CANCEL_CUTOFF_PASSED')
              ? cutoffNote(booking)
              : errorNotice(error, 'The refund was not taken.'),
          ),
      },
    );
  };

  return (
    <div data-testid="booking-detail" data-status={status} className="flex flex-col gap-3.5">
      <div className="flex items-center">
        <p className="type-label opacity-55">Booking</p>
        <Button variant="ghost" className="ml-auto" disabled={busy} onClick={onClose}>
          Close
        </Button>
      </div>

      <div>
        <h2 className="font-heading text-[24px] leading-tight font-extrabold tracking-tight">
          {booking.name ?? 'Customer'}
        </h2>
        <p className="text-[12px] opacity-60">{booking.phone ?? '—'}</p>
      </div>

      <Tag variant={bookingStatusTag(status)} className="self-start" data-testid="booking-status">
        {BOOKING_STATUS_DETAIL[status] ?? status}
      </Tag>

      {notice ? (
        <p
          role="alert"
          data-testid="booking-detail-notice"
          className="flex items-start gap-2 border-2 border-accent px-3 py-2 text-body text-accent-strong"
        >
          <AlertTriangle aria-hidden="true" className="mt-0.5 size-4 shrink-0" strokeWidth={2} />
          {notice}
        </p>
      ) : null}

      <div className="h-0.5 bg-divider" />

      <dl className="grid grid-cols-2 gap-3">
        <Figure label="Console" value={booking.stationName ?? '—'} />
        <Figure
          label="Starts"
          value={booking.startAt ? formatVenueDateTime(booking.startAt) : '—'}
          note={status === 'PAID' ? startsIn(booking.startAt, at) : undefined}
        />
        <Figure label="Play time" value={formatBlocks(booking.blocks ?? 0)} />
        <Figure
          label="Paid"
          value={formatBDT(booking.total ?? 0)}
          note={`incl. ${formatBDT(booking.packageFee ?? 0)} package fee`}
        />
      </dl>

      {canCheckIn(booking) ? (
        <Button
          variant="block"
          size="lg"
          data-testid="booking-check-in"
          loading={checkIn.isPending}
          disabled={busy}
          onClick={onCheckIn}
        >
          Check in &amp; print token
        </Button>
      ) : null}

      {token ? (
        <div
          data-testid="booking-token"
          className="flex items-center gap-3 border-2 border-accent px-3 py-2.5"
        >
          <TokenBadge token={token.tokenNo} issuedOn={token.tokenDate} today={day} />
          <p className="text-[11px] opacity-70">
            {`Token printed — ${stubMeta(booking)}. Seat them from the Floor page to start the clock.`}
          </p>
        </div>
      ) : null}

      {issued?.printJobId ? (
        <ReceiptPreview printJobId={issued.printJobId} today={day} />
      ) : null}

      {cancelling === 'available' ? (
        <Button
          variant="secondary"
          className="w-full"
          data-testid="booking-cancel"
          loading={cancel.isPending}
          disabled={busy}
          onClick={onCancel}
        >
          {`Cancel & refund ${formatBDT(booking.total ?? 0)}`}
        </Button>
      ) : null}

      {cancelling === 'locked' ? (
        <p
          data-testid="booking-cutoff-note"
          className="flex items-start gap-2 border-2 border-divider px-3 py-2 text-[12px] opacity-70"
        >
          <CalendarClock aria-hidden="true" className="mt-0.5 size-4 shrink-0" strokeWidth={2} />
          {cutoffNote(booking)}
        </p>
      ) : null}

      {cancelling === 'arrived' ? (
        <p data-testid="booking-arrived-note" className="text-[12px] opacity-70">
          The customer has arrived and holds a token — a refund now is a manager void of the
          transaction, not a cancellation.
        </p>
      ) : null}
    </div>
  );
}

function Figure({ label, value, note }: { label: string; value: string; note?: string }) {
  return (
    <div>
      <dt className="type-label opacity-55">{label}</dt>
      <dd className="font-heading text-[15px] font-extrabold tabular">{value}</dd>
      {note ? <dd className="text-[11px] opacity-55">{note}</dd> : null}
    </div>
  );
}
