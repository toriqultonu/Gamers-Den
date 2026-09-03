/**
 * Auth shell — S1 sits outside the app chrome (no sidebar, no topbar); the
 * screen paints its own two panels edge to edge.
 */
export default function AuthLayout({ children }: { children: React.ReactNode }) {
  return <div className="min-h-screen bg-bg text-text">{children}</div>;
}
