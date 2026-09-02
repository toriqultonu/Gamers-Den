/**
 * App shell — sidebar `NAV[role]` (Bookings gated by `booking_settings.enabled`),
 * topbar, signed-in card, sync chip, auto-lock.
 * Scaffolded in TASK F01; built in TASK F04.
 */
export default function AppLayout({ children }: { children: React.ReactNode }) {
  return <div className="min-h-screen bg-bg text-text">{children}</div>;
}
