'use client';

/**
 * The `(app)` chrome — sidebar, topbar, auto-lock, and the guard of last
 * resort.
 *
 * It is a client component because everything about it moves: the wall clock
 * ticks, the idle timer runs, the feature flag arrives from a query and can
 * take the Bookings item away mid-shift. The screens it wraps are unaffected —
 * they stay server components until they ask for a hook of their own
 * (frontend/ARCHITECTURE.md §5.1).
 *
 * The role it draws from is the live session's. `initialRole` — the routing
 * hint the middleware just read — only fills the first paint, so the sidebar
 * is correct before the refresh round-trip finishes and never flashes empty.
 */

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { useMemo } from 'react';
import { AppWindow } from 'lucide-react';
import { SidebarNav } from './sidebar-nav';
import { SignedInCard } from './signed-in-card';
import { TopBar } from './top-bar';
import { LockScreen } from './lock-screen';
import { AccessNotice } from './access-notice';
import { useSession } from '@/features/auth/session';
import { useAutoLock } from '@/features/auth/use-auto-lock';
import { useBookingSettings } from '@/features/bookings/queries';
import { autoLockMinutes, useTerminalSettings } from '@/features/settings/use-terminal-settings';
import { hasLiveTournament, useTournaments } from '@/features/tournaments/queries';
import { occupancyOf, useStations } from '@/features/sessions/queries';
import { useSyncStatus } from '@/features/sync/use-sync-status';
import { useLiveEvents } from '@/lib/sse';
import {
  appRouteOf,
  isRouteAllowed,
  landingPath,
  screenTitle,
  visibleNav,
  type NavId,
  type Role,
} from '@/lib/nav';

export type AppShellProps = {
  /** The role from the session cookie the middleware just checked. */
  initialRole: Role | null;
  children: React.ReactNode;
};

export function AppShell({ initialRole, children }: AppShellProps) {
  const session = useSession();
  const pathname = usePathname() ?? '/';
  const role = session.staff?.role ?? initialRole;
  const signedIn = session.status === 'authenticated' && session.staff !== null;

  // Every read below belongs to the shell itself. They stay off until there is
  // a token to send, so a restoring terminal does not fire four 401s.
  const bookingSettings = useBookingSettings({ enabled: signedIn });
  const terminalSettings = useTerminalSettings({ enabled: signedIn });
  const tournaments = useTournaments({ enabled: signedIn });
  const stations = useStations({ enabled: signedIn });
  const sync = useSyncStatus({ enabled: signedIn });

  // One `/events` subscription per terminal, mounted here rather than on the
  // screens: every screen reads the same cache, and a second stream would only
  // write the same rows twice. It carries the 10 s fallback with it, so the
  // floor stays true even while the stream is down (lib/sse.ts).
  useLiveEvents({ enabled: signedIn });

  useAutoLock({
    minutes: autoLockMinutes(terminalSettings.data),
    enabled: signedIn && !session.locked,
    onLock: session.lock,
  });

  const items = useMemo(
    () => (role ? visibleNav(role, { bookings: bookingSettings.data?.enabled === true }) : []),
    [role, bookingSettings.data?.enabled],
  );

  const badges = useMemo<Partial<Record<NavId, string>>>(
    () => (hasLiveTournament(tournaments.data) ? { tournaments: 'LIVE' } : {}),
    [tournaments.data],
  );

  // The middleware already turned this away; this is what shows when the
  // routing hint and the real role disagree (§4.3 — "render 403 as an access
  // notice"), and it is the same notice a 403 from the API earns.
  const allowed = role !== null && isRouteAllowed(role, pathname);

  return (
    <>
      {/* design.md §4 — under 768 the app says so rather than reflowing. */}
      <div className="hidden min-h-screen place-items-center p-6 text-center max-md:grid">
        <div className="flex max-w-xs flex-col gap-3">
          <AppWindow aria-hidden="true" className="mx-auto size-8" strokeWidth={2} />
          <h1 className="text-h3">Use a larger screen</h1>
          <p className="text-body opacity-75">
            Gamer&rsquo;s Den runs on the counter terminal — 1366×768 or wider.
          </p>
        </div>
      </div>

      <div className="flex min-h-screen bg-bg text-text max-md:hidden">
        <aside className="flex w-[218px] flex-none flex-col border-r-2 border-divider bg-surface max-lg:w-[56px]">
          <Link
            href={role ? landingPath(role) : '/floor'}
            className="flex items-center gap-3 border-b-2 border-divider px-4 py-4 focus-visible:outline-2 focus-visible:outline-accent focus-visible:-outline-offset-2"
          >
            <span
              aria-hidden="true"
              className="grid size-9 flex-none place-items-center bg-accent font-heading text-[17px] font-extrabold tracking-[-0.05em] text-on-accent"
            >
              GD
            </span>
            <span className="min-w-0 max-lg:hidden">
              <span className="block font-heading text-[15px] leading-tight font-extrabold tracking-[-0.02em]">
                GAMER&rsquo;S DEN
              </span>
              <span className="type-label block opacity-55">Bogura</span>
            </span>
          </Link>

          <SidebarNav items={items} activeRoute={appRouteOf(pathname)} badges={badges} />

          {session.staff ? (
            <SignedInCard
              staff={session.staff}
              terminal={session.terminal}
              onSignOut={session.signOut}
            />
          ) : null}
        </aside>

        <div className="flex min-w-0 flex-1 flex-col">
          <TopBar
            title={role ? screenTitle(pathname, role) : "Gamer's Den"}
            occupancy={occupancyOf(stations.data)}
            sync={sync}
          />
          <main className="min-h-0 flex-1">
            {allowed ? (
              children
            ) : (
              <AccessNotice
                screen={role ? screenTitle(pathname, 'ADMIN') : undefined}
                backHref={role ? landingPath(role) : '/floor'}
              />
            )}
          </main>
        </div>
      </div>

      {session.locked && session.staff ? (
        <LockScreen
          staff={session.staff}
          terminal={session.terminal}
          onUnlock={session.unlock}
          onSignOut={session.signOut}
        />
      ) : null}
    </>
  );
}
