'use client';

/**
 * S14 — Bookings, the pre-booking desk (design.md §1, S14; docs/bookings.md).
 *
 * Two columns. On the left the tabs over the table — Upcoming is the desk's
 * workload (paid for, not yet arrived), History is everything that has already
 * happened — with the rate card under them, because "how much is two hours on a
 * PS5" is the question the phone call opens with.
 *
 * On the right one rail with three faces, and never two at once: the idle hint
 * with its single **New booking** button, the **detail** of the row that was
 * clicked, or the pay-first **form**. Which face is showing is the only piece
 * of client state this screen owns (`bookingsTab`, `selectedBookingId`,
 * `bookingFormOpen` — §4.2), and losing it to a refresh costs nothing.
 *
 * **The feature flag guards the door, not the building** (docs/bookings.md §7).
 * With pre-booking switched off the sidebar item is already gone, but a
 * terminal sitting on this screen when an admin flips it — or an operator who
 * typed the URL — still has customers who have paid: the table, check-in and
 * cancel all keep working, and only *new* bookings are refused, which is what
 * the notice says and what the API's 409 `PREBOOKING_DISABLED` enforces.
 */

import { useMemo, useState } from 'react';
import { CalendarClock } from 'lucide-react';
import { AccessNotice } from './access-notice';
import { BookingDetail } from './booking-detail';
import { BookingForm } from './booking-form';
import { BookingTable, BookingTabs } from './booking-table';
import { Button } from '@/components/ui/button';
import { errorNotice, isApiError } from '@/lib/api';
import { formatBDT } from '@/lib/money';
import { serverNow, venueToday } from '@/lib/time';
import { useBookingSettings, useBookings, upcomingCount } from '@/features/bookings/queries';
import { useStations } from '@/features/sessions/queries';
import { usePricing, type Pricing } from '@/features/pos/queries';
import { useAppStore } from '@/features/pos/bill-store';

export function BookingsScreen() {
  const store = useAppStore.getState;
  const tab = useAppStore((state) => state.bookingsTab);
  const selectedBookingId = useAppStore((state) => state.selectedBookingId);
  const formOpen = useAppStore((state) => state.bookingFormOpen);

  const settings = useBookingSettings();
  const bookings = useBookings(tab);
  const upcoming = useBookings('upcoming');
  const stations = useStations();
  const pricing = usePricing();

  /** What the last confirm charged, when it was not what the box promised. */
  const [drift, setDrift] = useState<string | null>(null);

  const now = serverNow();
  const today = venueToday(now);
  const rows = bookings.data ?? [];
  const selected = useMemo(
    () => rows.find((row) => row.id === selectedBookingId) ?? null,
    [rows, selectedBookingId],
  );

  const enabled = settings.data?.enabled !== false;

  // A 403 on the list refuses the screen itself — there is nothing behind it.
  if (isApiError(bookings.error) && bookings.error.status === 403) {
    return <AccessNotice screen="Bookings" />;
  }

  return (
    <div data-testid="bookings-screen" className="flex min-h-0 flex-1">
      <div className="flex min-w-0 flex-1 flex-col gap-4 overflow-auto p-5">
        <div className="flex flex-wrap items-center gap-2">
          <BookingTabs
            tab={tab}
            upcoming={upcomingCount(upcoming.data)}
            onChange={(next) => store().setBookingsTab(next)}
          />
          <p className="ml-auto max-w-[520px] text-[12px] opacity-65">{policyLine(settings.data)}</p>
        </div>

        {!enabled ? (
          <p
            role="status"
            data-testid="prebooking-disabled"
            className="flex items-start gap-2 border-2 border-accent px-3 py-2 text-body text-accent-strong"
          >
            <CalendarClock aria-hidden="true" className="mt-0.5 size-4 shrink-0" strokeWidth={2} />
            Pre-booking is switched off in Setup — no new bookings can be taken. The ones already
            paid for still check in, seat and cancel.
          </p>
        ) : null}

        {bookings.isError ? (
          <p
            role="alert"
            data-testid="bookings-error"
            className="border-2 border-accent px-3 py-2 text-body text-accent-strong"
          >
            {errorNotice(bookings.error, 'The bookings could not be read.')}
          </p>
        ) : null}

        {bookings.isPending ? (
          <TableSkeleton />
        ) : (
          <BookingTable
            tab={tab}
            rows={rows}
            now={now}
            selectedId={selectedBookingId}
            onPick={(booking) => store().selectBooking(booking.id ?? null)}
          />
        )}

        <RateCard pricing={pricing.data} packageFee={settings.data?.packageFee ?? 0} />
      </div>

      <aside
        data-testid="bookings-rail"
        className="flex w-[356px] flex-none flex-col gap-3 overflow-auto border-l-2 border-divider bg-surface p-5 max-[1279px]:absolute max-[1279px]:right-0 max-[1279px]:top-0 max-[1279px]:bottom-0 max-[1279px]:z-10 max-[1279px]:shadow-lg"
      >
        {drift ? (
          <p
            role="alert"
            data-testid="booking-drift-notice"
            className="border-2 border-accent px-3 py-2 text-body text-accent-strong"
          >
            {drift}
          </p>
        ) : null}

        {formOpen ? (
          <BookingForm
            settings={settings.data}
            stations={stations.data}
            pricing={pricing.data}
            now={now}
            onClose={() => store().closeBookingForm()}
            onBooked={(booking, notice) => {
              setDrift(notice);
              store().setBookingsTab('upcoming');
              store().selectBooking(booking.id ?? null);
            }}
          />
        ) : selected ? (
          <BookingDetail
            booking={selected}
            now={now}
            today={today}
            onClose={() => store().selectBooking(null)}
          />
        ) : (
          <RailIdle
            enabled={enabled}
            onNew={() => {
              setDrift(null);
              store().openBookingForm();
            }}
          />
        )}
      </aside>
    </div>
  );
}

/** The idle rail: one button and a hint (design.md §1, S14). */
function RailIdle({ enabled, onNew }: { enabled: boolean; onNew: () => void }) {
  return (
    <div data-testid="bookings-rail-idle" className="flex flex-col gap-3">
      <Button
        variant="block"
        size="lg"
        data-testid="new-booking"
        disabled={!enabled}
        onClick={onNew}
      >
        New booking
      </Button>
      <p className="text-[12px] opacity-55">
        {enabled
          ? 'Select a booking from the list to check the customer in, print their token, or cancel.'
          : 'New bookings are switched off in Setup. Select a booking already paid for to check the customer in or cancel it.'}
      </p>
    </div>
  );
}

/** The policy strip: the two numbers a customer on the phone asks about. */
export function policyLine(
  settings: { packageFee?: number; cancelCutoffHours?: number } | undefined,
): string {
  const fee = settings?.packageFee ?? 0;
  const hours = settings?.cancelCutoffHours ?? 0;
  return `Play time + ${formatBDT(fee)} package fee, paid up front · full refund on cancellation until ${hours} h before start · the owner sets both in Setup.`;
}

/**
 * The rate card. `currentBlockPrice` already carries the morning discount if
 * we are inside the window, and the morning row is shown when the rate card
 * declares one — the hours themselves are an open flag (design.md §8), so they
 * are printed as the server states them rather than assumed.
 */
export function RateCard({
  pricing,
  packageFee,
}: {
  pricing: Pricing[] | undefined;
  packageFee: number;
}) {
  const rows = pricing ?? [];
  if (rows.length === 0) return null;

  return (
    <section data-testid="booking-rate-card" className="flex flex-col gap-2 border-2 border-divider p-4">
      <h2 className="type-label opacity-55">Rate card</h2>
      <dl className="grid grid-cols-2 gap-4">
        {rows.map((rate) => (
          <div key={rate.consoleType}>
            <dt className="font-heading text-[15px] font-extrabold">{rate.consoleType}</dt>
            <dd className="text-[13px] tabular">
              {`${formatBDT(rate.perHalfHour ?? 0)} / 30 min · ${formatBDT(rate.perHour ?? 0)} / hr`}
            </dd>
            {rate.morningDiscountPct ? (
              <dd className="text-[11px] opacity-55">
                {`${rate.morningDiscountPct}% off ${rate.morningStart ?? ''}–${rate.morningEnd ?? ''}`}
              </dd>
            ) : null}
          </div>
        ))}
      </dl>
      <p className="text-[11px] opacity-55">
        {`Every booking adds a ${formatBDT(packageFee)} package fee. Play time is priced for the booked hour, not the hour it is sold in.`}
      </p>
    </section>
  );
}

/** The loading state, shaped like the table it becomes (design.md §1). */
function TableSkeleton() {
  return (
    <div data-testid="bookings-skeleton" aria-busy="true" className="flex flex-col gap-2">
      <div className="h-6 border-b-2 border-divider" />
      {Array.from({ length: 6 }, (_, row) => (
        <div key={row} className="h-8 border-b border-divider bg-surface opacity-40" />
      ))}
    </div>
  );
}
