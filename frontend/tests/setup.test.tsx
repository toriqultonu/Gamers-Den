/**
 * S10 — Setup / Menu & stock (design.md §1 S10 row, docs/bookings.md §1,
 * frontend/ARCHITECTURE.md §4.3).
 *
 * State-table assertions, not snapshots. The four rules this screen is not
 * allowed to get wrong:
 *
 *  - **role sectioning.** Admin configures the venue, Manager configures the
 *    shelves, and a cashier gets the access notice. A manager's screen must not
 *    merely *disable* the owner's sections — they are absent, and the queries
 *    behind them (`GET /staff`, Admin-only) are never fired.
 *  - **the pre-booking switches are Admin's**, and switching them off takes the
 *    Bookings nav item off every terminal on the same frame the save settles.
 *  - **`STAFF_ON_SHIFT`** renders as a notice with the roster untouched — the
 *    removal was never drawn ahead of the server.
 *  - **the default printer persists** because it lives in `['printers']`, not
 *    in the card's own state: a remount shows the server's choice.
 */

import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SetupScreen } from '@/components/domain/setup-screen';
import { AppShell } from '@/components/domain/app-shell';
import { SessionProvider } from '@/features/auth/session';
import { makeQueryClient } from '@/lib/query-client';
import { SESSION_COOKIE } from '@/lib/session-cookie';
import { forgetSession, resetIdempotencyKeys } from '@/lib/api';
import { resetServerTime } from '@/lib/time';
import type { Role } from '@/lib/nav';
import {
  canEditBookingSettings,
  canOpenSetup,
  canSetDefaultPrinter,
  changedPricing,
  createStaffSchema,
  pricingDraft,
  roleNote,
  setupSections,
  stationRemovable,
} from '@/features/setup/schemas';
import { defaultPrinter, printerReady, printerStatusLabel } from '@/features/printing/printers';

const NOW = '2026-09-03T14:00:00Z';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn(), prefetch: vi.fn(), refresh: vi.fn(), back: vi.fn() }),
  usePathname: () => '/setup',
  useSearchParams: () => new URLSearchParams(),
}));

/* ------------------------------------------------------------- fixtures */

const STAFF_BY_ROLE: Record<Role, { id: number; name: string; role: Role }> = {
  ADMIN: { id: 1, name: 'Rumi Haque', role: 'ADMIN' },
  MANAGER: { id: 2, name: 'Farhan Reza', role: 'MANAGER' },
  CASHIER: { id: 4, name: 'Sabbir Ahmed', role: 'CASHIER' },
};

const STATIONS = [
  { id: 1, name: 'Titan', consoleType: 'PS5', status: 'AVAILABLE', floorState: 'FREE' },
  { id: 2, name: 'Nova', consoleType: 'PS4', status: 'AVAILABLE', floorState: 'RUNNING' },
];

const PRICING = [
  {
    consoleType: 'PS5',
    perHour: 240,
    perHalfHour: 120,
    currentBlockPrice: 120,
    morningDiscountPct: 25,
    morningStart: '10:00',
    morningEnd: '14:00',
  },
  {
    consoleType: 'PS4',
    perHour: 180,
    perHalfHour: 90,
    currentBlockPrice: 90,
    morningDiscountPct: 25,
    morningStart: '10:00',
    morningEnd: '14:00',
  },
];

const STAFF = [
  { id: 1, name: 'Rumi Haque', role: 'ADMIN', active: true },
  { id: 2, name: 'Farhan Reza', role: 'MANAGER', active: true },
  { id: 4, name: 'Sabbir Ahmed', role: 'CASHIER', active: true },
];

const ITEMS = [
  {
    id: 1,
    name: 'Cola 500ml',
    category: 'BEVERAGE',
    price: 60,
    stock: 24,
    reorderAt: 6,
    active: true,
    lowStock: false,
    outOfStock: false,
  },
  {
    id: 3,
    name: 'Chicken Roll',
    category: 'FOOD',
    price: 120,
    stock: 3,
    reorderAt: 6,
    active: true,
    lowStock: true,
    outOfStock: false,
  },
];

const PRINTERS = [
  { id: 'usb-1', name: 'Counter 80mm', status: 'ONLINE' },
  { id: 'usb-2', name: 'Back office', status: 'OUT_OF_PAPER' },
];

/* --------------------------------------------------------------- server */

const fetchMock = vi.fn();

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', Date: new Date(NOW).toUTCString() },
  });
}

function conflict(code: string, message: string) {
  return json({ error: { code, message, traceId: 't-1' } }, 409);
}

/** A stream that stays open: the shell subscribes, and nothing else happens. */
function idleEventStream(): Response {
  return {
    ok: true,
    status: 200,
    headers: new Headers({ 'Content-Type': 'text/event-stream' }),
    body: new ReadableStream<Uint8Array>({ start() {} }),
  } as unknown as Response;
}

type Handlers = {
  staff?: () => Response;
  deleteStaff?: () => Response;
  deleteStation?: () => Response;
  createStation?: () => Response;
  bookingSettings?: (body: Record<string, unknown>) => Response;
  pricing?: (body: unknown) => Response;
  patchItem?: () => Response;
};

const calls: {
  method: string;
  path: string;
  body: Record<string, unknown>;
  raw: unknown;
}[] = [];

/** The venue as the mock keeps it: the default printer and the booking flag move. */
let defaultPrinterId = 'usb-1';
let bookingsEnabled = true;

function serve(handlers: Handlers = {}, role: Role = 'ADMIN') {
  fetchMock.mockImplementation((input: RequestInfo, init?: RequestInit) => {
    const url = new URL(String(input));
    const path = url.pathname.replace('/api/v1', '');
    const method = (init?.method ?? 'GET').toUpperCase();
    const raw = init?.body ? JSON.parse(String(init.body)) : {};
    const body = (Array.isArray(raw) ? {} : raw) as Record<string, unknown>;
    calls.push({ method, path, body, raw });

    if (path === '/auth/login' || path === '/auth/refresh') {
      return json({
        accessToken: 'access-token',
        expiresIn: 900,
        staff: STAFF_BY_ROLE[role],
        terminal: 'COUNTER-1',
        tokenType: 'Bearer',
      });
    }
    if (path === '/events') return idleEventStream();
    if (path === '/sync/status') return json({ state: 'SYNCED', pendingOps: 0 });
    if (path === '/tournaments') return json([]);
    if (path === '/terminal-settings') {
      return json({ theme: 'DARK', autoLockMin: 0, receiptCopies: 1, sound: true });
    }

    if (method === 'GET' && path === '/booking-settings') {
      return json({ enabled: bookingsEnabled, packageFee: 100, cancelCutoffHours: 4 });
    }
    if (method === 'PUT' && path === '/booking-settings') {
      if (handlers.bookingSettings) return handlers.bookingSettings(body);
      bookingsEnabled = body.enabled === true;
      return json({
        enabled: bookingsEnabled,
        packageFee: Number(body.packageFee ?? 100),
        cancelCutoffHours: Number(body.cancelCutoffHours ?? 4),
      });
    }

    if (method === 'GET' && path === '/stations') return json(STATIONS);
    if (method === 'POST' && path === '/stations') {
      return handlers.createStation?.() ?? json({ id: 9, ...body }, 201);
    }
    if (method === 'DELETE' && path.startsWith('/stations/')) {
      return handlers.deleteStation?.() ?? new Response(null, { status: 204 });
    }

    if (method === 'GET' && path === '/pricing') return json(PRICING);
    if (method === 'PUT' && path === '/pricing') {
      return handlers.pricing?.(raw) ?? json(PRICING);
    }

    if (method === 'GET' && path === '/staff') return handlers.staff?.() ?? json(STAFF);
    if (method === 'POST' && path === '/staff') return json({ id: 7, ...body, active: true }, 201);
    if (method === 'DELETE' && path.startsWith('/staff/')) {
      return handlers.deleteStaff?.() ?? new Response(null, { status: 204 });
    }

    if (method === 'GET' && path === '/items') return json(ITEMS);
    if (method === 'POST' && path === '/items') return json({ id: 11, ...body }, 201);
    if (method === 'PATCH' && path.startsWith('/items/')) {
      return handlers.patchItem?.() ?? json({ ...ITEMS[0], ...body });
    }
    if (method === 'DELETE' && path.startsWith('/items/')) {
      return new Response(null, { status: 204 });
    }

    if (method === 'GET' && path === '/printers') {
      const rows = PRINTERS.map((printer) => ({
        ...printer,
        isDefault: printer.id === defaultPrinterId,
      }));
      // "Default first", as the contract promises.
      return json([...rows].sort((a, b) => Number(b.isDefault) - Number(a.isDefault)));
    }
    if (method === 'PUT' && path === '/printers/default') {
      defaultPrinterId = String(body.printerId);
      const chosen = PRINTERS.find((printer) => printer.id === defaultPrinterId);
      return json({ ...chosen, isDefault: true });
    }
    if (method === 'POST' && path.endsWith('/test')) {
      return json({ id: 901, status: 'QUEUED', type: 'RECEIPT' }, 201);
    }

    return json({});
  });
}

let client: QueryClient;

function renderSetup(role: Role) {
  client = makeQueryClient();
  client.setDefaultOptions({ queries: { retry: false } });
  return render(
    <QueryClientProvider client={client}>
      <SetupScreen role={role} />
    </QueryClientProvider>,
  );
}

/** S10 inside the real shell — the only way to watch the sidebar react. */
function renderInShell(role: Role) {
  document.cookie = `${SESSION_COOKIE}=${role}; Path=/`;
  client = makeQueryClient();
  client.setDefaultOptions({ queries: { retry: false } });
  return render(
    <QueryClientProvider client={client}>
      <SessionProvider>
        <AppShell initialRole={role}>
          <SetupScreen role={role} />
        </AppShell>
      </SessionProvider>
    </QueryClientProvider>,
  );
}

async function openSetup(role: Role = 'ADMIN') {
  const user = userEvent.setup();
  renderSetup(role);
  await waitFor(() => expect(screen.getByTestId('setup-screen')).toBeInTheDocument());
  return user;
}

async function navLabels(): Promise<string[]> {
  const nav = await screen.findByRole('navigation', { name: 'Main' });
  return within(nav)
    .getAllByRole('link')
    .map((link) => link.textContent?.trim() ?? '');
}

const requests = (method: string, path: string) =>
  calls.filter((call) => call.method === method && call.path === path);

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock);
  fetchMock.mockReset();
  calls.length = 0;
  defaultPrinterId = 'usb-1';
  bookingsEnabled = true;
  window.localStorage.clear();
  forgetSession();
  resetServerTime();
  resetIdempotencyKeys();
  serve();
});

afterEach(() => {
  document.cookie = `${SESSION_COOKIE}=; Path=/; Max-Age=0`;
  vi.unstubAllGlobals();
});

/* ------------------------------------------------------------------ pure */

describe('the role sectioning', () => {
  it('gives the owner the venue and the manager the shelves', () => {
    expect(setupSections('ADMIN')).toEqual([
      'stations',
      'pricing',
      'prebooking',
      'staff',
      'menu',
      'printing',
    ]);
    expect(setupSections('MANAGER')).toEqual(['menu', 'printing']);
    expect(setupSections('CASHIER')).toEqual([]);
    expect(setupSections(null)).toEqual([]);
  });

  it('opens the screen for Manager+ only', () => {
    expect(canOpenSetup('ADMIN')).toBe(true);
    expect(canOpenSetup('MANAGER')).toBe(true);
    expect(canOpenSetup('CASHIER')).toBe(false);
    expect(canOpenSetup(null)).toBe(false);
  });

  it('keeps the pre-booking switches and the venue printer Admin-only', () => {
    expect(canEditBookingSettings('ADMIN')).toBe(true);
    expect(canEditBookingSettings('MANAGER')).toBe(false);
    expect(canEditBookingSettings('CASHIER')).toBe(false);

    expect(canSetDefaultPrinter('ADMIN')).toBe(true);
    expect(canSetDefaultPrinter('MANAGER')).toBe(false);
  });

  it('tells each role what its screen is for', () => {
    expect(roleNote('ADMIN')).toMatch(/pre-booking/i);
    expect(roleNote('MANAGER')).toMatch(/stock/i);
    expect(roleNote('CASHIER')).toMatch(/not open to your role/i);
  });
});

describe('the pure helpers the sections lean on', () => {
  it('refuses to offer Remove on a console the floor is using', () => {
    expect(stationRemovable(STATIONS[0])).toBe(true);
    expect(stationRemovable(STATIONS[1])).toBe(false);
    expect(stationRemovable({ ...STATIONS[1], floorState: 'MAINTENANCE' })).toBe(true);
  });

  it('sends only the console types whose numbers actually moved', () => {
    const draft = pricingDraft(PRICING);
    expect(changedPricing(PRICING, draft)).toEqual([]);

    const bumped = { ...draft, PS4: { ...draft.PS4, perHalfHour: 100 } };
    expect(changedPricing(PRICING, bumped).map((rate) => rate.consoleType)).toEqual(['PS4']);
  });

  it('holds a new PIN to four digits before it is ever posted', () => {
    const base = { name: 'Rakib Hossain', role: 'CASHIER' as const };
    expect(createStaffSchema.safeParse({ ...base, pin: '12' }).success).toBe(false);
    expect(createStaffSchema.safeParse({ ...base, pin: '12a4' }).success).toBe(false);
    expect(createStaffSchema.safeParse({ ...base, pin: '1234' }).success).toBe(true);
  });

  it('reads the printer list the way the card draws it', () => {
    const rows = [
      { id: 'usb-1', name: 'Counter', status: 'ONLINE', isDefault: true },
      { id: 'usb-2', name: 'Office', status: 'OUT_OF_PAPER', isDefault: false },
    ];
    expect(defaultPrinter(rows)?.id).toBe('usb-1');
    expect(printerReady(rows[0])).toBe(true);
    expect(printerReady(rows[1])).toBe(false);
    expect(printerStatusLabel('OUT_OF_PAPER')).toBe('Out of paper');
  });
});

/* ------------------------------------------------------- rendered sections */

describe('what each role sees', () => {
  it('gives the owner every section and every rail form', async () => {
    await openSetup('ADMIN');

    for (const section of [
      'stations-section',
      'pricing-section',
      'prebooking-section',
      'staff-section',
      'menu-section',
      'printing-card',
    ]) {
      expect(await screen.findByTestId(section)).toBeInTheDocument();
    }

    expect(screen.getByTestId('add-station-form')).toBeInTheDocument();
    expect(screen.getByTestId('add-staff-form')).toBeInTheDocument();
    expect(screen.getByTestId('add-item-form')).toBeInTheDocument();
  });

  it('gives the manager menu, stock and the printer card — nothing else', async () => {
    serve({}, 'MANAGER');
    await openSetup('MANAGER');

    expect(await screen.findByTestId('menu-section')).toBeInTheDocument();
    expect(screen.getByTestId('printing-card')).toBeInTheDocument();
    expect(screen.getByTestId('add-item-form')).toBeInTheDocument();

    expect(screen.queryByTestId('stations-section')).not.toBeInTheDocument();
    expect(screen.queryByTestId('pricing-section')).not.toBeInTheDocument();
    expect(screen.queryByTestId('prebooking-section')).not.toBeInTheDocument();
    expect(screen.queryByTestId('staff-section')).not.toBeInTheDocument();
    expect(screen.queryByTestId('add-station-form')).not.toBeInTheDocument();
    expect(screen.queryByTestId('add-staff-form')).not.toBeInTheDocument();
  });

  it('never fires the Admin-only roster read on a manager’s screen', async () => {
    serve({}, 'MANAGER');
    await openSetup('MANAGER');
    await screen.findByTestId('menu-section');

    expect(requests('GET', '/staff')).toHaveLength(0);
  });

  it('lets the manager test a ticket but not move the venue’s default', async () => {
    serve({}, 'MANAGER');
    await openSetup('MANAGER');

    await screen.findByTestId('printer-list');
    expect(screen.queryByRole('button', { name: 'Make default' })).not.toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Test ticket' }).length).toBeGreaterThan(0);
    expect(screen.getByTestId('printer-default-locked')).toBeInTheDocument();
  });

  it('hides the whole screen from a cashier and explains why', async () => {
    serve({}, 'CASHIER');
    renderSetup('CASHIER');

    expect(await screen.findByTestId('access-notice')).toHaveTextContent(/Setup/);
    expect(screen.queryByTestId('setup-screen')).not.toBeInTheDocument();
    expect(screen.queryByTestId('menu-section')).not.toBeInTheDocument();
  });
});

/* ------------------------------------------------------- pre-booking + nav */

describe('the pre-booking controls', () => {
  it('takes the Bookings nav item away the moment the owner switches it off', async () => {
    const user = userEvent.setup();
    renderInShell('ADMIN');

    await waitFor(async () => expect(await navLabels()).toContain('Bookings'));
    await screen.findByTestId('prebooking-section');

    await user.click(within(screen.getByRole('radiogroup', { name: 'Pre-booking' })).getByRole(
      'radio',
      { name: 'Off' },
    ));
    await user.click(screen.getByRole('button', { name: 'Save pre-booking settings' }));

    await waitFor(() => expect(requests('PUT', '/booking-settings')).toHaveLength(1));
    expect(requests('PUT', '/booking-settings')[0].body).toMatchObject({
      enabled: false,
      packageFee: 100,
      cancelCutoffHours: 4,
    });

    await waitFor(async () => expect(await navLabels()).not.toContain('Bookings'));
    expect(await navLabels()).toContain('Setup');
    expect(screen.getByTestId('prebooking-saved')).toHaveTextContent(
      /hidden on every terminal/i,
    );
  });

  it('sends the fee and the cutoff with the switch — they are one decision', async () => {
    const user = await openSetup('ADMIN');
    await screen.findByTestId('prebooking-section');

    const fee = screen.getByLabelText('Package fee (৳, paid up front)');
    await user.clear(fee);
    await user.type(fee, '150');
    await user.click(screen.getByRole('button', { name: 'Save pre-booking settings' }));

    await waitFor(() => expect(requests('PUT', '/booking-settings')).toHaveLength(1));
    expect(requests('PUT', '/booking-settings')[0].body).toMatchObject({
      enabled: true,
      packageFee: 150,
      cancelCutoffHours: 4,
    });
  });

  it('keeps the typed values and says so when the save is refused', async () => {
    serve({ bookingSettings: () => conflict('CONFLICT', 'nope') });
    const user = await openSetup('ADMIN');
    await screen.findByTestId('prebooking-section');

    const cutoff = screen.getByLabelText('Free cancellation until (hours before)');
    await user.clear(cutoff);
    await user.type(cutoff, '6');
    await user.click(screen.getByRole('button', { name: 'Save pre-booking settings' }));

    expect(await screen.findByTestId('prebooking-notice')).toBeInTheDocument();
    expect(cutoff).toHaveValue('6');
    expect(screen.queryByTestId('prebooking-saved')).not.toBeInTheDocument();
  });
});

/* ------------------------------------------------------------------ staff */

describe('removing staff', () => {
  it('renders STAFF_ON_SHIFT as a notice and leaves the roster exactly as it was', async () => {
    serve({
      deleteStaff: () => conflict('STAFF_ON_SHIFT', 'Farhan Reza is on shift'),
    });
    const user = await openSetup('ADMIN');

    const table = within(await screen.findByTestId('staff-section')).getByRole('table');
    const row = within(table).getByText('Farhan Reza').closest('tr') as HTMLElement;
    await user.click(within(row).getByRole('button', { name: 'Remove' }));

    expect(await screen.findByTestId('staff-notice')).toHaveTextContent(
      /on shift — close the shift first/i,
    );
    // Never optimistic: the row is still there, still removable.
    expect(within(table).getByText('Farhan Reza')).toBeInTheDocument();
    expect(within(row).getByRole('button', { name: 'Remove' })).toBeEnabled();
  });

  it('deactivates on success and re-reads the roster', async () => {
    const user = await openSetup('ADMIN');

    const table = within(await screen.findByTestId('staff-section')).getByRole('table');
    const row = within(table).getByText('Sabbir Ahmed').closest('tr') as HTMLElement;
    await user.click(within(row).getByRole('button', { name: 'Remove' }));

    await waitFor(() => expect(requests('DELETE', '/staff/4')).toHaveLength(1));
    expect(screen.queryByTestId('staff-notice')).not.toBeInTheDocument();
    await waitFor(() => expect(requests('GET', '/staff').length).toBeGreaterThan(1));
  });

  it('offers no Remove against the owner account', async () => {
    await openSetup('ADMIN');

    const table = within(await screen.findByTestId('staff-section')).getByRole('table');
    const owner = within(table).getByText('Rumi Haque').closest('tr') as HTMLElement;
    expect(within(owner).queryByRole('button', { name: 'Remove' })).not.toBeInTheDocument();
  });

  it('will not post a PIN that is not four digits', async () => {
    const user = await openSetup('ADMIN');

    await user.type(screen.getByLabelText('Full name'), 'Rakib Hossain');
    await user.click(within(screen.getByRole('group', { name: 'Role' })).getByText('Cashier'));
    await user.type(screen.getByLabelText('4-digit PIN'), '12');
    await user.click(screen.getByRole('button', { name: 'Add staff' }));

    expect(await screen.findByText('The PIN is four digits.')).toBeInTheDocument();
    expect(requests('POST', '/staff')).toHaveLength(0);
  });
});

/* --------------------------------------------------------------- stations */

describe('stations', () => {
  it('renders STATION_IN_USE without removing the row', async () => {
    serve({ deleteStation: () => conflict('STATION_IN_USE', 'Titan has a live session') });
    const user = await openSetup('ADMIN');

    const table = within(await screen.findByTestId('stations-section')).getByRole('table');
    const row = within(table).getByText('Titan').closest('tr') as HTMLElement;
    await user.click(within(row).getByRole('button', { name: 'Remove' }));

    expect(await screen.findByTestId('stations-notice')).toHaveTextContent(
      /in use — end the session/i,
    );
    expect(within(table).getByText('Titan')).toBeInTheDocument();
  });

  it('does not even offer Remove on a console with a session running', async () => {
    await openSetup('ADMIN');

    const table = within(await screen.findByTestId('stations-section')).getByRole('table');
    const row = within(table).getByText('Nova').closest('tr') as HTMLElement;
    expect(within(row).getByRole('button', { name: 'In use' })).toBeDisabled();
  });

  it('surfaces DUPLICATE_NAME on the add form and keeps what was typed', async () => {
    serve({ createStation: () => conflict('DUPLICATE_NAME', 'taken') });
    const user = await openSetup('ADMIN');

    const name = within(screen.getByTestId('add-station-form')).getByLabelText('Name');
    await user.type(name, 'Titan');
    await user.click(screen.getByRole('button', { name: 'Add station' }));

    expect(await screen.findByTestId('add-station-notice')).toHaveTextContent(
      /already taken/i,
    );
    expect(name).toHaveValue('Titan');
  });
});

/* ------------------------------------------------------- pricing and stock */

describe('the rate card', () => {
  it('sends only the console type that changed, and says new blocks only', async () => {
    const user = await openSetup('ADMIN');
    await screen.findByTestId('pricing-section');

    expect(screen.getByRole('button', { name: 'Save rates' })).toBeDisabled();

    const card = within(screen.getByTestId('pricing-card-PS4'));
    const half = card.getByLabelText('Per 30 min (৳)');
    await user.clear(half);
    await user.type(half, '100');
    await user.click(screen.getByRole('button', { name: 'Save rates' }));

    await waitFor(() => expect(requests('PUT', '/pricing')).toHaveLength(1));
    const sent = requests('PUT', '/pricing')[0].raw as { consoleType: string }[];
    expect(sent.map((rate) => rate.consoleType)).toEqual(['PS4']);
    expect(await screen.findByTestId('pricing-saved')).toHaveTextContent(/New blocks only/i);
  });
});

describe('the menu editor', () => {
  it('patches the absolute counted stock, not a delta', async () => {
    const user = await openSetup('ADMIN');
    await screen.findByTestId('menu-section');

    const stock = screen.getByLabelText('Stock — Cola 500ml');
    await user.clear(stock);
    await user.type(stock, '18');

    const row = stock.closest('tr') as HTMLElement;
    await user.click(within(row).getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(requests('PATCH', '/items/1')).toHaveLength(1));
    expect(requests('PATCH', '/items/1')[0].body).toEqual({
      price: 60,
      stock: 18,
      reorderAt: 6,
    });
  });

  it('keeps Save inert until a number actually moves', async () => {
    await openSetup('ADMIN');
    await screen.findByTestId('menu-section');

    const row = screen.getByLabelText('Stock — Cola 500ml').closest('tr') as HTMLElement;
    expect(within(row).getByRole('button', { name: 'Save' })).toBeDisabled();
  });
});

/* -------------------------------------------------------------- printing */

describe('the printing card', () => {
  it('shows every device with its live status and marks the default', async () => {
    await openSetup('ADMIN');

    const list = await screen.findByTestId('printer-list');
    expect(within(list).getByText('Counter 80mm')).toBeInTheDocument();
    expect(within(list).getByText('Online')).toBeInTheDocument();
    expect(within(list).getByText('Out of paper')).toBeInTheDocument();
    expect(within(list).getByTestId('printer-default-tag')).toBeInTheDocument();
  });

  it('persists a new default through the server, not in the card’s own state', async () => {
    const user = await openSetup('ADMIN');
    await screen.findByTestId('printer-list');

    await user.click(screen.getByRole('button', { name: 'Make default' }));

    await waitFor(() => expect(requests('PUT', '/printers/default')).toHaveLength(1));
    expect(requests('PUT', '/printers/default')[0].body).toEqual({ printerId: 'usb-2' });

    // The card reads `['printers']`, so the moved flag arrives from the re-read.
    await waitFor(() => {
      const rows = screen.getAllByTestId('printer-row');
      expect(rows[0]).toHaveTextContent('Back office');
      expect(rows[0]).toHaveAttribute('data-default', 'true');
    });

    // …and it survives the screen being torn down and rebuilt, because it was
    // never component state: the same query client answers from cache first.
    const remount = render(
      <QueryClientProvider client={client}>
        <SetupScreen role="ADMIN" />
      </QueryClientProvider>,
    );
    await waitFor(() => {
      const rows = within(remount.container).getAllByTestId('printer-row');
      expect(rows[0]).toHaveTextContent('Back office');
      expect(rows[0]).toHaveAttribute('data-default', 'true');
    });
    expect(
      within(remount.container).queryByRole('button', { name: 'Make default' }),
    ).toBeInTheDocument();
  });

  it('queues a test ticket as an ordinary job and reports its number', async () => {
    const user = await openSetup('ADMIN');
    await screen.findByTestId('printer-list');

    await user.click(screen.getAllByRole('button', { name: 'Test ticket' })[0]);

    expect(await screen.findByTestId('printing-test-queued')).toHaveTextContent('#901');
  });

  it('reports the venue paper width instead of offering a switch that writes nowhere', async () => {
    await openSetup('ADMIN');

    const width = screen.getByRole('radiogroup', { name: 'Paper width' });
    expect(within(width).getByRole('radio', { name: '80 mm' })).toBeChecked();
    expect(within(width).getByRole('radio', { name: '58 mm' })).toBeDisabled();
    expect(screen.getByTestId('paper-width-note')).toHaveTextContent(/48 columns/);
  });
});
