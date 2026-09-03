'use client';

/**
 * BookingTable + tabs — docs/design.md §2: variants "upcoming, history";
 * state "row selected (accent outline), clickable"; props `tab, rows, onPick`.
 *
 * The tabs are the screen's spine: Upcoming is what has been paid for and not
 * yet arrived (with its count, because that number is the desk's workload), and
 * History is everything that has already happened. Which side a booking falls
 * on is the server's answer to `?tab=`, not a filter applied here — a check-in
 * moves a row across, and only the server knows a check-in happened.
 *
 * The two empty states are different sentences on purpose (design.md §1): an
 * empty Upcoming is an invitation to take a booking, an empty History is simply
 * a young venue.
 */

import { Tag } from '@/components/ui/tag';
import { DataTable, type Column } from '@/components/ui/data-table';
import { formatBlocks } from '@/components/ui/time-stepper';
import { formatBDT } from '@/lib/money';
import { formatVenueDateTime } from '@/lib/time';
import {
  BOOKING_TABS,
  bookingStartNote,
  bookingStatusLabel,
  bookingStatusTag,
  type Booking,
  type BookingTab,
} from '@/features/bookings/schemas';

export const BOOKING_TAB_LABELS: Record<BookingTab, string> = {
  upcoming: 'Upcoming',
  history: 'History',
};

export type BookingTableProps = {
  tab: BookingTab;
  rows: readonly Booking[];
  onPick: (booking: Booking) => void;
  selectedId?: number | null;
  /** Server-offset now — the "in ~3 h" note is a claim about venue time. */
  now: number;
};

export function BookingTable({ tab, rows, onPick, selectedId = null, now }: BookingTableProps) {
  return (
    <DataTable
      caption={`${BOOKING_TAB_LABELS[tab]} bookings`}
      columns={bookingColumns(now)}
      rows={rows}
      rowKey={(booking) => String(booking.id)}
      selectedKey={selectedId === null ? null : String(selectedId)}
      onSelect={onPick}
      empty={<span data-testid={`bookings-empty-${tab}`}>{emptyMessage(tab)}</span>}
    />
  );
}

/** design.md §1, S14 — the two tabs say different things when they are bare. */
export function emptyMessage(tab: BookingTab): string {
  return tab === 'history'
    ? 'No past bookings yet.'
    : 'No upcoming bookings — take one with New booking.';
}

export function bookingColumns(now: number): Column<Booking>[] {
  return [
    {
      key: 'station',
      header: 'Console',
      render: (booking) => (
        <span className="font-heading font-extrabold">{booking.stationName ?? '—'}</span>
      ),
    },
    {
      key: 'customer',
      header: 'Customer',
      render: (booking) => (
        <span className="block min-w-0">
          <span className="block font-heading font-extrabold">{booking.name ?? '—'}</span>
          <span className="block text-[11px] opacity-55">{booking.phone ?? '—'}</span>
        </span>
      ),
    },
    {
      key: 'starts',
      header: 'Starts',
      render: (booking) => (
        <span className="block">
          <span className="block">
            {booking.startAt ? formatVenueDateTime(booking.startAt) : '—'}
          </span>
          <span className="block text-[11px] opacity-55">{bookingStartNote(booking, now)}</span>
        </span>
      ),
    },
    {
      key: 'length',
      header: 'Length',
      render: (booking) => <span className="opacity-70">{formatBlocks(booking.blocks ?? 0)}</span>,
    },
    {
      key: 'paid',
      header: 'Paid',
      align: 'right',
      render: (booking) => formatBDT(booking.total ?? 0),
    },
    {
      key: 'status',
      header: 'Status',
      render: (booking) => (
        <span className="flex flex-wrap items-center gap-1.5">
          <Tag variant={bookingStatusTag(booking.status)}>{bookingStatusLabel(booking)}</Tag>
          {booking.overlapping ? (
            <Tag variant="outline" data-testid="booking-overlap-flag">
              Console double-booked
            </Tag>
          ) : null}
        </span>
      ),
    },
  ];
}

/** The tab strip, with the count that makes Upcoming worth looking at. */
export function BookingTabs({
  tab,
  onChange,
  upcoming,
}: {
  tab: BookingTab;
  onChange: (tab: BookingTab) => void;
  upcoming: number;
}) {
  return (
    <div role="tablist" aria-label="Bookings" className="flex items-center gap-2">
      {BOOKING_TABS.map((candidate) => {
        const on = candidate === tab;
        return (
          <button
            key={candidate}
            type="button"
            role="tab"
            aria-selected={on}
            data-state={on ? 'selected' : 'unselected'}
            data-testid={`bookings-tab-${candidate}`}
            onClick={() => onChange(candidate)}
            className={
              on
                ? 'cursor-pointer rounded-none border-2 border-accent bg-accent px-3.5 py-2 font-heading text-[13px] font-extrabold text-on-accent'
                : 'cursor-pointer rounded-none border-2 border-divider bg-transparent px-3.5 py-2 font-heading text-[13px] font-extrabold text-text hover:bg-neutral-200'
            }
          >
            {candidate === 'upcoming'
              ? `${BOOKING_TAB_LABELS.upcoming} · ${upcoming}`
              : BOOKING_TAB_LABELS.history}
          </button>
        );
      })}
    </div>
  );
}
