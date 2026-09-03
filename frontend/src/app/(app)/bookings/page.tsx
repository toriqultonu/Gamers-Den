/**
 * S14 — Bookings (TASK F10).
 *
 * A server component that mounts the screen: the tabs, the rail and the
 * pay-first form are a selection, a mutation and a form — the client side of
 * frontend/ARCHITECTURE.md §5.1. The sidebar already hides this route while
 * `booking_settings.enabled` is false; the screen itself still renders the
 * feature notice, because a flag flipped mid-shift leaves paid bookings that
 * staff must still be able to check in (docs/bookings.md §7).
 */

import { BookingsScreen } from '@/components/domain/bookings-screen';

export default function BookingsPage() {
  return <BookingsScreen />;
}
