'use client';

/**
 * Booking reads: `['bookings', tab]`, `['bookings', id]`, `['booking-settings']`.
 *
 * F04 needs one of them early: the sidebar hides S14 entirely while
 * `booking_settings.enabled` is false (frontend/ARCHITECTURE.md §4.3), so the
 * shell reads the flag on every mount. The tab and detail reads land with S14
 * itself in F10.
 */

import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { queryKeys } from '@/lib/query-keys';
import { bookingSettingsSchema, type BookingSettings } from './schemas';

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
