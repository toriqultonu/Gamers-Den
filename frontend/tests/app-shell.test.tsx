/**
 * The `(app)` shell — sidebar `NAV[role]` filtered by the feature flags,
 * topbar, and the auto-lock (frontend/ARCHITECTURE.md §4.3, design.md §1/§6).
 *
 * `nav.test.ts` proves the map; this proves the shell renders that map and
 * nothing else — including the case the flag governs, where S14 has to be
 * absent from the rail rather than merely disabled.
 */

import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AppShell } from '@/components/domain/app-shell';
import { SessionProvider } from '@/features/auth/session';
import { makeQueryClient } from '@/lib/query-client';
import { SESSION_COOKIE } from '@/lib/session-cookie';
import { forgetSession } from '@/lib/api';
import type { Role } from '@/lib/nav';

const replace = vi.fn();
let pathname = '/floor';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace, push: vi.fn(), prefetch: vi.fn(), refresh: vi.fn(), back: vi.fn() }),
  usePathname: () => pathname,
  useSearchParams: () => new URLSearchParams(),
}));

const fetchMock = vi.fn();

const STAFF: Record<Role, { id: number; name: string; role: Role; avatarColor: string | null }> = {
  ADMIN: { id: 1, name: 'Rumi Haque', role: 'ADMIN', avatarColor: '#ec3013' },
  MANAGER: { id: 2, name: 'Farhan Reza', role: 'MANAGER', avatarColor: null },
  CASHIER: { id: 4, name: 'Sabbir Ahmed', role: 'CASHIER', avatarColor: null },
};

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

type Backend = {
  role?: Role;
  bookingsEnabled?: boolean;
  autoLockMin?: number;
  tournaments?: { id: number; status: string }[];
  stations?: { id: number; floorState: string }[];
  sync?: { state: string; lastSyncedAt?: string; pendingOps?: number };
};

/** A stream that stays open: the shell subscribes, and nothing else happens. */
function idleEventStream(): Response {
  return {
    ok: true,
    status: 200,
    headers: new Headers({ 'Content-Type': 'text/event-stream' }),
    body: new ReadableStream<Uint8Array>({ start() {} }),
  } as unknown as Response;
}

function serve({
  role = 'ADMIN',
  bookingsEnabled = false,
  autoLockMin = 0,
  tournaments = [],
  stations = [],
  sync = { state: 'SYNCED', pendingOps: 0 },
}: Backend = {}) {
  fetchMock.mockImplementation((input: string) => {
    const url = String(input);
    if (url.endsWith('/auth/login') || url.endsWith('/auth/refresh')) {
      return Promise.resolve(
        json({
          accessToken: 'access-token',
          expiresIn: 900,
          staff: STAFF[role],
          terminal: 'T1',
          tokenType: 'Bearer',
        }),
      );
    }
    if (url.endsWith('/booking-settings')) {
      return Promise.resolve(
        json({ enabled: bookingsEnabled, packageFee: 100, cancelCutoffHours: 4 }),
      );
    }
    if (url.endsWith('/terminal-settings')) {
      return Promise.resolve(json({ theme: 'DARK', autoLockMin, receiptCopies: 1, sound: true }));
    }
    if (url.endsWith('/events')) return Promise.resolve(idleEventStream());
    if (url.endsWith('/sync/status')) return Promise.resolve(json(sync));
    if (url.endsWith('/tournaments')) return Promise.resolve(json(tournaments));
    if (url.endsWith('/stations')) return Promise.resolve(json(stations));
    if (url.endsWith('/auth/logout')) return Promise.resolve(new Response(null, { status: 204 }));
    return Promise.resolve(json({ error: { code: 'NOT_FOUND', message: url } }, 404));
  });
}

function renderShell(role: Role = 'ADMIN') {
  document.cookie = `${SESSION_COOKIE}=${role}; Path=/`;
  return render(
    <QueryClientProvider client={makeQueryClient()}>
      <SessionProvider>
        <AppShell initialRole={role}>
          <p>screen body</p>
        </AppShell>
      </SessionProvider>
    </QueryClientProvider>,
  );
}

/** The sidebar's item labels, in order. */
async function navLabels(): Promise<string[]> {
  const nav = await screen.findByRole('navigation', { name: 'Main' });
  return within(nav)
    .getAllByRole('link')
    .map((link) => link.textContent?.trim() ?? '');
}

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock);
  fetchMock.mockReset();
  replace.mockReset();
  pathname = '/floor';
  window.localStorage.clear();
  forgetSession();
  serve();
});

afterEach(() => {
  document.cookie = `${SESSION_COOKIE}=; Path=/; Max-Age=0`;
  vi.unstubAllGlobals();
});

describe('the sidebar', () => {
  it('draws the owner every screen', async () => {
    serve({ role: 'ADMIN', bookingsEnabled: true });
    renderShell('ADMIN');

    await waitFor(async () =>
      expect(await navLabels()).toEqual([
        'Overview',
        'Floor',
        'Bookings',
        'Point of sale',
        'Inventory',
        'Members',
        'Tournaments',
        'Shift close',
        'Expenses',
        'Reports',
        'Setup',
        'Settings',
      ]),
    );
  });

  it('draws the manager everything but Overview, and labels S10 honestly', async () => {
    serve({ role: 'MANAGER', bookingsEnabled: true });
    renderShell('MANAGER');

    const labels = await waitFor(async () => {
      const found = await navLabels();
      expect(found).toContain('Menu & stock');
      return found;
    });
    expect(labels).not.toContain('Overview');
    expect(labels).not.toContain('Setup');
    expect(labels).toContain('Reports');
  });

  it('draws the cashier no Overview, Reports or Setup', async () => {
    serve({ role: 'CASHIER', bookingsEnabled: true });
    renderShell('CASHIER');

    const labels = await waitFor(async () => {
      const found = await navLabels();
      expect(found).toContain('Bookings');
      return found;
    });
    for (const hidden of ['Overview', 'Reports', 'Setup', 'Menu & stock']) {
      expect(labels).not.toContain(hidden);
    }
  });

  it('marks the screen you are on', async () => {
    pathname = '/floor';
    serve({ role: 'CASHIER' });
    renderShell('CASHIER');

    const nav = await screen.findByRole('navigation', { name: 'Main' });
    expect(within(nav).getByRole('link', { name: /Floor/ })).toHaveAttribute(
      'aria-current',
      'page',
    );
  });
});

describe('the pre-booking feature flag', () => {
  it('leaves Bookings out of the rail entirely when it is off', async () => {
    serve({ role: 'ADMIN', bookingsEnabled: false });
    renderShell('ADMIN');

    // Wait for a flag-independent item so the absence below is a real absence.
    await waitFor(async () => expect(await navLabels()).toContain('Floor'));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/booking-settings'),
      expect.anything(),
    ));

    expect(await navLabels()).not.toContain('Bookings');
    const nav = await screen.findByRole('navigation', { name: 'Main' });
    expect(within(nav).queryByRole('link', { name: /Bookings/ })).not.toBeInTheDocument();
  });

  it('puts it back when the owner switches pre-booking on', async () => {
    serve({ role: 'ADMIN', bookingsEnabled: true });
    renderShell('ADMIN');

    await waitFor(async () => expect(await navLabels()).toContain('Bookings'));
  });
});

describe('the topbar', () => {
  it('titles the screen and counts the busy consoles', async () => {
    pathname = '/floor';
    serve({
      role: 'CASHIER',
      stations: [
        { id: 1, floorState: 'RUNNING' },
        { id: 2, floorState: 'RESERVED' },
        { id: 3, floorState: 'FREE' },
        { id: 4, floorState: 'MAINTENANCE' },
      ],
    });
    renderShell('CASHIER');

    expect(await screen.findByRole('heading', { level: 1, name: 'Floor' })).toBeInTheDocument();
    expect(await screen.findByText('2/4')).toBeInTheDocument();
  });

  it('carries the sync chip from the very first screen', async () => {
    renderShell('ADMIN');
    const chip = await screen.findByTestId('sync-chip');
    await waitFor(() => expect(chip).toHaveAttribute('data-state', 'synced'));
  });

  it('shows the cloud being unreachable without touching anything else', async () => {
    serve({ role: 'ADMIN', sync: { state: 'OFFLINE', lastSyncedAt: '2026-09-03T08:30:00Z' } });
    renderShell('ADMIN');

    const chip = await screen.findByTestId('sync-chip');
    await waitFor(() => expect(chip).toHaveTextContent('Offline since 14:30'));
    expect(screen.getByText('screen body')).toBeInTheDocument();
  });
});

describe('the LIVE mark', () => {
  it('lights Tournaments while an event is being played', async () => {
    serve({ role: 'CASHIER', tournaments: [{ id: 7, status: 'LIVE' }] });
    renderShell('CASHIER');

    const nav = await screen.findByRole('navigation', { name: 'Main' });
    await waitFor(() =>
      expect(within(nav).getByRole('link', { name: /Tournaments/ })).toHaveTextContent('LIVE'),
    );
  });

  it('stays dark while nothing is live', async () => {
    serve({ role: 'CASHIER', tournaments: [{ id: 7, status: 'OPEN' }] });
    renderShell('CASHIER');

    const nav = await screen.findByRole('navigation', { name: 'Main' });
    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    expect(within(nav).getByRole('link', { name: /Tournaments/ })).not.toHaveTextContent('LIVE');
  });
});

describe('a role standing where it may not', () => {
  it('renders the access notice instead of the screen', async () => {
    pathname = '/overview';
    serve({ role: 'CASHIER' });
    renderShell('CASHIER');

    expect(await screen.findByTestId('access-notice')).toBeInTheDocument();
    expect(screen.queryByText('screen body')).not.toBeInTheDocument();
  });

  it('renders the screen when the role does have it', async () => {
    pathname = '/overview';
    serve({ role: 'ADMIN' });
    renderShell('ADMIN');

    expect(await screen.findByText('screen body')).toBeInTheDocument();
    expect(screen.queryByTestId('access-notice')).not.toBeInTheDocument();
  });
});

describe('the signed-in card', () => {
  it('names who is on the terminal', async () => {
    serve({ role: 'MANAGER' });
    renderShell('MANAGER');

    expect(await screen.findByText('Farhan Reza')).toBeInTheDocument();
    expect(screen.getByText('Manager · T1')).toBeInTheDocument();
  });

  it('signs out through the server, so the refresh family is revoked', async () => {
    const user = userEvent.setup();
    serve({ role: 'CASHIER' });
    renderShell('CASHIER');

    await user.click(await screen.findByRole('button', { name: /Sign out/ }));

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringContaining('/auth/logout'),
        expect.anything(),
      ),
    );
    await waitFor(() => expect(replace).toHaveBeenCalledWith('/login'));
    expect(document.cookie).not.toContain(`${SESSION_COOKIE}=CASHIER`);
  });
});

describe('auto-lock', () => {
  it('drops the lock over the shell after the terminal-settings idle time', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      serve({ role: 'CASHIER', autoLockMin: 2 });
      renderShell('CASHIER');

      // The idle timer only arms once the settings have arrived.
      await screen.findByText('Sabbir Ahmed');
      await waitFor(() =>
        expect(fetchMock).toHaveBeenCalledWith(
          expect.stringContaining('/terminal-settings'),
          expect.anything(),
        ),
      );
      expect(screen.queryByTestId('lock-screen')).not.toBeInTheDocument();

      act(() => vi.advanceTimersByTime(2 * 60_000));

      expect(await screen.findByTestId('lock-screen')).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it('never locks while the setting is Off', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      serve({ role: 'CASHIER', autoLockMin: 0 });
      renderShell('CASHIER');

      await screen.findByText('Sabbir Ahmed');
      act(() => vi.advanceTimersByTime(60 * 60_000));

      expect(screen.queryByTestId('lock-screen')).not.toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });
});
