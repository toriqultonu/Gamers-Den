/**
 * Auth shell — S1 sits outside the app chrome (no sidebar, no topbar).
 * Scaffolded in TASK F01; built in TASK F04.
 */
export default function AuthLayout({ children }: { children: React.ReactNode }) {
  return <div className="min-h-screen bg-bg text-text">{children}</div>;
}
