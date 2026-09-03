/**
 * F16 — the responsive pass: design.md §4, one test per row of that table.
 *
 * | ≥1280      | full three-column layouts                                     |
 * | 1024–1279  | POS ticket column behind Preview · Overview alerts rail starts
 *                collapsed · Bookings rail overlays                          |
 * | 768–1023   | sidebar → icons · 1-up station grid · bill panel → drawer     |
 * | <768       | "Use a larger screen"                                         |
 *
 * jsdom has no CSS engine, so two different things are asserted two different
 * ways, and neither pretends to be the other:
 *
 *  - **Behaviour** — the parts §4 makes stateful rather than styled. "Starts
 *    collapsed" and "collapses behind a Preview button" are defaults a person
 *    can then override, so they live in React and are driven here through a
 *    stubbed `matchMedia` and real clicks.
 *  - **Declaration** — the parts that are pure layout. A grid that goes 1-up
 *    and a rail that becomes an overlay are one utility each, and the utility
 *    *is* the behaviour; what is pinned is that the element still carries it,
 *    so deleting `max-lg:grid-cols-1` in a refactor fails here rather than on
 *    the owner's tablet.
 */

import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { AppShell } from '@/components/domain/app-shell';
import { BookingsScreen } from '@/components/domain/bookings-screen';
import { FloorScreen } from '@/components/domain/floor-screen';
import { OverviewScreen } from '@/components/domain/overview-screen';
import { PosScreen } from '@/components/domain/pos-screen';
import { SessionProvider } from '@/features/auth/session';
import { makeQueryClient } from '@/lib/query-client';
import { SESSION_COOKIE } from '@/lib/session-cookie';
import { forgetSession, resetIdempotencyKeys } from '@/lib/api';
import { resetServerTime } from '@/lib/time';
import { resetPosStore } from '@/features/pos/bill-store';
import { WIDE_VIEWPORT } from '@/lib/use-media-query';

const NOW = '2026-09-03T12:00:00Z';

vi.mock('next/navigation', () => ({
  useRouter: () => ({
    replace: vi.fn(),
    push: vi.fn(),
    prefetch: vi.fn(),
    refresh: vi.fn(),
    back: vi.fn(),
  }),
  usePathname: () => '/floor',
  useSearchParams: () => new URLSearchParams(),
}));

/* --------------------------------------------------------------- server */

const fetchMock = vi.fn();

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', Date: new Date(NOW).toUTCString() },
  });
}

/** A stream that stays open: the shell subscribes and nothing else happens. */
function idleEventStream(): Response {
  return {
    ok: true,
    status: 200,
    headers: new Headers({ 'Content-Type': 'text/event-stream' }),
    body: new ReadableStream<Uint8Array>({ start() {} }),
  } as unknown as Response;
}

const STATIONS = [
  { id: 1, name: 'Nexus', consoleType: 'PS5', floorState: 'FREE', status: 'AVAILABLE' },
  { id: 2, name: 'Vortex', consoleType: 'PS5', floorState: 'FREE', status: 'AVAILABLE' },
];

const ITEMS = [
  { id: 11, name: 'Cola', category: 'DRINK', price: 60, stockQty: 20, active: true },
];

function serve() {
  fetchMock.mockImplementation((input: RequestInfo) => {
    const url = String(input);
    const path = new URL(url).pathname.replace('/api/v1', '');
    if (path === '/events') return Promise.resolve(idleEventStream());
    if (path === '/auth/refresh' || path === '/auth/login') {
      return Promise.resolve(
        json({
          accessToken: 'access-token',
          expiresIn: 900,
          staff: { id: 1, name: 'Rumi Haque', role: 'ADMIN', avatarColor: null },
          terminal: 'T1',
          tokenType: 'Bearer',
        }),
      );
    }
    if (path === '/stations') return Promise.resolve(json(STATIONS));
    if (path === '/items') return Promise.resolve(json(ITEMS));
    if (path === '/booking-settings') {
      return Promise.resolve(json({ enabled: true, packageFee: 100, cancelCutoffHours: 2 }));
    }
    if (path === '/terminal-settings') {
      return Promise.resolve(json({ theme: 'DARK', autoLockMin: 0, receiptCopies: 1, sound: true }));
    }
    if (path === '/members') {
      return Promise.resolve(json({ content: [], page: 0, size: 50, totalElements: 0 }));
    }
    if (path === '/overview') return Promise.resolve(json({}));
    if (path === '/sync/status') return Promise.resolve(json({ state: 'SYNCED', pendingOps: 0 }));
    return Promise.resolve(json([]));
  });
}

/**
 * The viewport, as the only thing in jsdom that can carry one: `matchMedia`.
 * §4's queries are all `min-width`, so `wide` is "≥1280" and everything the
 * components ask is answered from that one fact.
 */
function stubViewport(wide: boolean) {
  vi.stubGlobal(
    'matchMedia',
    vi.fn().mockImplementation((query: string) => ({
      matches: query === WIDE_VIEWPORT ? wide : false,
      media: query,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })),
  );
}

let client: QueryClient;

function mount(node: React.ReactElement) {
  client = makeQueryClient();
  client.setDefaultOptions({ queries: { retry: false } });
  return render(
    <QueryClientProvider client={client}>
      <SessionProvider>{node}</SessionProvider>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock);
  fetchMock.mockReset();
  resetPosStore();
  forgetSession();
  resetServerTime();
  resetIdempotencyKeys();
  window.localStorage.clear();
  serve();
});

afterEach(() => {
  document.cookie = `${SESSION_COOKIE}=; Path=/; Max-Age=0`;
  vi.unstubAllGlobals();
});

/* ------------------------------------------------------------------ <768 */

describe('under 768 — “Use a larger screen”', () => {
  it('says so instead of reflowing, and hides the chrome behind the notice', async () => {
    document.cookie = `${SESSION_COOKIE}=ADMIN; Path=/`;
    mount(
      <AppShell initialRole="ADMIN">
        <p>screen body</p>
      </AppShell>,
    );

    const notice = await screen.findByRole('heading', { name: 'Use a larger screen' });
    // The notice is the phone-width branch; the app frame is the other one.
    expect(notice.closest('div')?.parentElement?.className).toContain('max-md:grid');
    const frame = document.querySelector('.max-md\\:hidden');
    expect(frame).not.toBeNull();
  });
});

/* -------------------------------------------------------------- 768–1023 */

describe('768–1023 — sidebar icons, 1-up stations, the bill in a drawer', () => {
  it('drops the sidebar to icons and keeps the labels for wider screens', async () => {
    document.cookie = `${SESSION_COOKIE}=ADMIN; Path=/`;
    mount(
      <AppShell initialRole="ADMIN">
        <p>screen body</p>
      </AppShell>,
    );

    const nav = await screen.findByRole('navigation', { name: 'Main' });
    const aside = nav.closest('aside');
    expect(aside?.className).toContain('max-lg:w-[56px]');

    // The label goes, the icon stays — the item is still a link at 56px.
    const label = nav.querySelector('span.max-lg\\:hidden');
    expect(label).not.toBeNull();
  });

  it('stacks the console grid 1-up, in the layout and in its skeleton', async () => {
    mount(<FloorScreen />);

    // The skeleton is the same grid, so the stack survives the loading state.
    expect((await screen.findByTestId('floor-skeleton')).className).toContain(
      'max-lg:grid-cols-1',
    );

    const [card] = await screen.findAllByTestId('station-card');
    const grid = card.closest('div.grid');
    expect(grid?.className).toContain('max-lg:grid-cols-1');
    expect(grid?.className).toContain('grid-cols-2');
  });

  it('shuts the bill panel into a drawer, and opens it from the menu column', async () => {
    const user = userEvent.setup();
    stubViewport(false);
    mount(<PosScreen />);

    const panel = await screen.findByTestId('bill-panel');
    expect(panel).toHaveAttribute('data-drawer-open', 'false');
    expect(panel.className).toContain('max-lg:hidden');

    const toggle = screen.getByTestId('bill-drawer-toggle');
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await user.click(toggle);

    const open = screen.getByTestId('bill-panel');
    expect(open).toHaveAttribute('data-drawer-open', 'true');
    // Over the menu, not beside it — there is no room for a column at 768.
    expect(open.className).toContain('max-lg:absolute');
    expect(open.className).not.toContain('max-lg:hidden');
    expect(screen.getByTestId('bill-drawer-close')).toBeInTheDocument();
  });
});

/* ------------------------------------------------------------ 1024–1279 */

describe('1024–1279 — the three-column screens give up their third column', () => {
  it('hides the POS ticket column behind the Preview button', async () => {
    const user = userEvent.setup();
    stubViewport(false);
    mount(<PosScreen />);

    const ticket = await screen.findByTestId('ticket-column');
    expect(ticket).toHaveAttribute('data-open', 'false');
    expect(ticket.className).toContain('max-[1279px]:hidden');

    await user.click(screen.getByTestId('preview-toggle'));

    const shown = screen.getByTestId('ticket-column');
    expect(shown).toHaveAttribute('data-open', 'true');
    expect(shown.className).not.toContain('max-[1279px]:hidden');
  });

  it('starts the Overview alerts rail collapsed, and leaves it open above 1280', async () => {
    stubViewport(false);
    mount(<OverviewScreen role="ADMIN" />);
    await waitFor(() =>
      expect(screen.getByTestId('alerts-rail')).toHaveAttribute('data-state', 'collapsed'),
    );

    // The same rail on the counter terminal's own 1440×900 is open.
    vi.unstubAllGlobals();
    vi.stubGlobal('fetch', fetchMock);
    stubViewport(true);
    resetPosStore();
    mount(<OverviewScreen role="ADMIN" />);
    await waitFor(() =>
      expect(screen.getAllByTestId('alerts-rail')[1]).not.toHaveAttribute(
        'data-state',
        'collapsed',
      ),
    );
  });

  it('overlays the Bookings rail, anchored to the screen it covers', async () => {
    mount(<BookingsScreen />);

    const rail = await screen.findByTestId('bookings-rail');
    expect(rail.className).toContain('max-[1279px]:absolute');
    // An overlay with no positioned ancestor hangs off the viewport and covers
    // the topbar — the screen it belongs to has to be the frame it sits in.
    expect(screen.getByTestId('bookings-screen').className).toContain('relative');
  });
});

/* ----------------------------------------------------------------- ≥1280 */

describe('at 1280 and up — the full three-column layouts', () => {
  it('leaves the POS with its menu, bill and ticket all in place', async () => {
    stubViewport(true);
    mount(<PosScreen />);

    // Nothing is hidden by a state flag at this width: both side columns carry
    // only the narrow-viewport utilities, which do not apply here.
    const panel = await screen.findByTestId('bill-panel');
    expect(panel).toHaveAttribute('data-drawer-open', 'false');
    expect(panel.className).toContain('w-[348px]');
    expect(screen.getByTestId('ticket-column').className).toContain('w-[306px]');
  });
});
