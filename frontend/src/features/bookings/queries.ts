'use client';

/**
 * Booking reads: `['bookings', tab]`, `['bookings', id]`, `['booking-settings']`.
 *
 * F04 needs one of them early: the sidebar hides S14 entirely while
 * `booking_settings.enabled` is false (frontend/ARCHITECTURE.md §4.3), so the
 * shell reads the flag on every mount. The tab and detail reads below are S14's
 * own (F10).
 */

import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { queryKeys } from '@/lib/query-keys';
import {
  bookingSettingsSchema,
  type Booking as BookingSchema,
  type BookingSettings,
  type BookingTab,
} from './schemas';

/** Any role may read the settings; only Admin may write them (api-contract.md §2). */
export function bookingSettingsQueryOptions() {
  return {
    queryKey: queryKeys.bookings.settings(),
    queryFn: async (): Promise<BookingSettings> =>
      bookingSettingsSchema.parse(await api.get('/booking-settings')),
    // An admin flipping the switch in S10 should reach the other terminals'
    // sidebars within a minute; it is not worth an SSE channel of its own.
    staleTime: 60_000,
  };
}

export function useBookingSettings(options: { enabled?: boolean } = {}) {
  return useQuery({ ...bookingSettingsQueryOptions(), enabled: options.enabled ?? true });
}

/* ------------------------------------------------------------- the two tabs */

export type Booking = BookingSchema;

/**
 * `GET /bookings?tab=` — the tab, as the server slices it: upcoming = PAID
 * soonest first, history = ARRIVED/USED/CANCELLED most recent first.
 *
 * The split is deliberately the server's, not a filter over one list. Which
 * side of it a booking belongs to is a lifecycle question ("has this customer
 * arrived?"), and `booking-update` invalidates both keys for exactly that
 * reason (lib/sse.ts): a check-in moves a row from one tab to the other and
 * only the server knows it happened.
 */
export function bookingsQueryOptions(tab: BookingTab) {
  return {
    queryKey: queryKeys.bookings.tab(tab),
    queryFn: () => api.get<Booking[]>('/bookings', { query: { tab } }),
  };
}

export function useBookings(tab: BookingTab, options: { enabled?: boolean } = {}) {
  return useQuery({ ...bookingsQueryOptions(tab), enabled: options.enabled ?? true });
}

/**
 * `GET /bookings/{id}` — the rail's own read.
 *
 * The row the table holds is the same shape, so the rail opens on it and this
 * read refines it: `cancellable` is computed against the server clock at fetch,
 * and a booking sitting open on screen while its cutoff passes should learn
 * about it. The SSE handler writes this key directly (`booking-update`).
 */
export function bookingQueryOptions(id: number) {
  return {
    queryKey: queryKeys.bookings.detail(id),
    queryFn: () => api.get<Booking>(`/bookings/${id}`),
  };
}

export function useBookingDetail(id: number | null | undefined) {
  return useQuery({
    ...bookingQueryOptions(id ?? 0),
    enabled: typeof id === 'number' && id > 0,
  });
}

/** The count beside the Upcoming tab — every slot paid for and still waiting. */
export function upcomingCount(rows: Booking[] | undefined): number {
  return (rows ?? []).filter((row) => row.status === 'PAID').length;
}
