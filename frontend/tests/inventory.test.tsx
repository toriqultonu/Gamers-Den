/**
 * S5 — Inventory (design.md §1, S5 row).
 *
 * State-table assertions, not snapshots. What is pinned here:
 *
 *  - the stock verdict — OUT / Reorder / Watch / Healthy — and that the server's
 *    own `lowStock` / `outOfStock` flags win over the derived middle band;
 *  - the rail lists exactly what is at or under its reorder point, emptiest
 *    first, and says so plainly when nothing is;
 *  - the table is **read-only**: no control on this screen writes anything;
 *  - the equipment register reports the consoles the API actually knows about,
 *    and admits that peripherals are counted off-system;
 *  - the five states: default, loading skeleton shaped like the table, empty,
 *    error, and a 403 rendered as the access notice.
 */

import { render, screen, waitFor, within } from '@testing-library/react';
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { InventoryScreen } from '@/components/domain/inventory-screen';
import { makeQueryClient } from '@/lib/query-client';
import { forgetSession, resetIdempotencyKeys } from '@/lib/api';
import { resetServerTime } from '@/lib/time';
import {
  lowStockItems,
  lowStockNote,
  stockRows,
  stockState,
  stockTotals,
  type Item,
} from '@/features/setup/schemas';

const NOW = '2026-09-03T14:00:00Z';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn(), prefetch: vi.fn(), refresh: vi.fn() }),
  usePathname: () => '/inventory',
  useSearchParams: () => new URLSearchParams(),
}));

/* ------------------------------------------------------------- fixtures */

const COLA: Item = {
  id: 1,
  name: 'Cola 500ml',
  category: 'BEVERAGE',
  price: 60,
  stock: 24,
  reorderAt: 6,
  active: true,
  available: 24,
  lowStock: false,
  outOfStock: false,
};

/** 8 left against a reorder point of 6 → inside 1.6×, the Watch band. */
const CHIPS: Item = {
  id: 2,
  name: 'Chips',
  category: 'SNACK',
  price: 40,
  stock: 8,
  reorderAt: 6,
  active: true,
  available: 8,
  lowStock: false,
  outOfStock: false,
};

const ROLL: Item = {
  id: 3,
  name: 'Chicken Roll',
  category: 'FOOD',
  price: 120,
  stock: 3,
  reorderAt: 6,
  active: true,
  available: 3,
  lowStock: true,
  outOfStock: false,
};

const CABLE: Item = {
  id: 4,
  name: 'HDMI cable',
  category: 'EXTRAS',
  price: 350,
  stock: 0,
  reorderAt: 2,
  active: false,
  available: 0,
  lowStock: true,
  outOfStock: true,
};

const ITEMS: Item[] = [COLA, CHIPS, ROLL, CABLE];

const STATIONS = [
  { id: 1, name: 'Titan', consoleType: 'PS5', status: 'AVAILABLE', floorState: 'RUNNING' },
  { id: 2, name: 'Nova', consoleType: 'PS4', status: 'MAINTENANCE', floorState: 'MAINTENANCE' },
];

/* --------------------------------------------------------------- server */

const fetchMock = vi.fn();

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', Date: new Date(NOW).toUTCString() },
  });
}

type Handlers = { items?: () => Response; stations?: () => Response };

const calls: { method: string; path: string }[] = [];

function serve(handlers: Handlers = {}) {
  fetchMock.mockImplementation((input: RequestInfo, init?: RequestInit) => {
    const url = new URL(String(input));
    const path = url.pathname.replace('/api/v1', '');
    const method = (init?.method ?? 'GET').toUpperCase();
    calls.push({ method, path });

    if (path === '/items') return handlers.items?.() ?? json(ITEMS);
    if (path === '/stations') return handlers.stations?.() ?? json(STATIONS);
    return json({});
  });
}

let client: QueryClient;

function renderScreen() {
  client = makeQueryClient();
  client.setDefaultOptions({ queries: { retry: false } });
  return render(
    <QueryClientProvider client={client}>
      <InventoryScreen />
    </QueryClientProvider>,
  );
}

async function openScreen() {
  renderScreen();
  await waitFor(() => expect(screen.getByRole('table')).toBeInTheDocument());
}

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock);
  fetchMock.mockReset();
  calls.length = 0;
  forgetSession();
  resetServerTime();
  resetIdempotencyKeys();
  serve();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

/* ------------------------------------------------------------------ pure */

describe('the stock verdict', () => {
  it('takes the server’s flags first and derives only the middle band', () => {
    expect(stockState(COLA)).toBe('HEALTHY');
    expect(stockState(CHIPS)).toBe('WATCH');
    expect(stockState(ROLL)).toBe('REORDER');
    expect(stockState(CABLE)).toBe('OUT');
  });

  it('trusts `lowStock` even when the two numbers alone would look healthy', () => {
    expect(stockState({ ...COLA, lowStock: true })).toBe('REORDER');
    expect(stockState({ ...COLA, outOfStock: true })).toBe('OUT');
  });

  it('never calls a shelf with no reorder point a watch', () => {
    expect(stockState({ ...COLA, reorderAt: 0, stock: 1 })).toBe('HEALTHY');
  });
});

describe('the low-stock rail', () => {
  it('lists what is at or under its reorder point, emptiest first', () => {
    expect(lowStockItems(ITEMS).map((item) => item.name)).toEqual(['HDMI cable', 'Chicken Roll']);
    expect(lowStockItems([COLA, CHIPS])).toEqual([]);
    expect(lowStockItems(undefined)).toEqual([]);
  });

  it('says how far under, because that is why the card is there', () => {
    expect(lowStockNote(ROLL)).toBe('3 left, reorder point is 6');
    expect(lowStockNote(CABLE)).toBe('None left, reorder point is 2');
  });
});

describe('the table’s own arithmetic', () => {
  it('counts lines, units and what needs an order', () => {
    expect(stockTotals(ITEMS)).toEqual({ lines: 4, units: 35, reorder: 1, out: 1 });
    expect(stockTotals(undefined)).toEqual({ lines: 0, units: 0, reorder: 0, out: 0 });
  });

  it('orders by category then name, retired rows included — this is a stock record', () => {
    expect(stockRows(ITEMS).map((item) => item.name)).toEqual([
      'Cola 500ml',
      'HDMI cable',
      'Chicken Roll',
      'Chips',
    ]);
  });
});

/* ---------------------------------------------------------------- screen */

describe('the default state', () => {
  it('draws every stock row with its verdict, retired rows marked', async () => {
    await openScreen();

    const table = screen.getByRole('table');
    expect(within(table).getByText('Cola 500ml')).toBeInTheDocument();
    expect(within(table).getByText('Chips')).toBeInTheDocument();
    expect(within(table).getByText('Healthy')).toBeInTheDocument();
    expect(within(table).getByText('Watch')).toBeInTheDocument();
    expect(within(table).getByText('Reorder')).toBeInTheDocument();
    expect(within(table).getByText('Out of stock')).toBeInTheDocument();
    expect(within(table).getByText('Off menu')).toBeInTheDocument();
  });

  it('reads the whole menu, retired rows and all — no `active` filter', async () => {
    await openScreen();
    expect(calls.filter((call) => call.path === '/items')).toHaveLength(1);
  });

  it('is read-only: nothing on this screen writes', async () => {
    await openScreen();

    expect(screen.queryAllByRole('button')).toHaveLength(0);
    expect(screen.queryAllByRole('textbox')).toHaveLength(0);
    expect(
      screen.getByText(/Stock counts are edited from Setup \(Admin\) or Menu & stock \(Manager\)/i),
    ).toBeInTheDocument();
  });

  it('puts the two shelves that need ordering on the rail, and nothing else', async () => {
    await openScreen();

    const cards = screen.getAllByTestId('low-stock-card');
    expect(cards).toHaveLength(2);
    expect(cards[0]).toHaveTextContent('HDMI cable');
    expect(cards[0]).toHaveTextContent('None left, reorder point is 2');
    expect(cards[1]).toHaveTextContent('Chicken Roll');
  });
});

describe('the equipment register', () => {
  it('lists the consoles the API keeps a register of, with what each is doing', async () => {
    await openScreen();

    const register = screen.getByTestId('equipment-register');
    expect(within(register).getByText('Titan')).toBeInTheDocument();
    expect(within(register).getByText(/PS5 · running/i)).toBeInTheDocument();
    expect(within(register).getByText(/PS4 · Maintenance/i)).toBeInTheDocument();
  });

  it('says plainly that peripherals are not tracked, rather than inventing numbers', async () => {
    await openScreen();

    expect(
      within(screen.getByTestId('equipment-register')).getByText(
        /Controllers, headsets and cables are counted off-system/i,
      ),
    ).toBeInTheDocument();
  });

  it('has an empty state of its own before the first console exists', async () => {
    serve({ stations: () => json([]) });
    await openScreen();

    expect(await screen.findByTestId('equipment-empty')).toHaveTextContent(
      /No consoles registered/i,
    );
  });
});

describe('the other four states', () => {
  it('loads a skeleton shaped like the table it becomes', async () => {
    let release: (() => void) | null = null;
    const held = new Promise<void>((resolve) => {
      release = resolve;
    });
    serve({
      items: (() => {
        // The first read never settles until the test lets it.
        return held.then(() => json(ITEMS)) as unknown as Response;
      }) as () => Response,
    });

    renderScreen();

    expect(await screen.findByTestId('inventory-skeleton')).toBeInTheDocument();
    expect(screen.getByTestId('low-stock-skeleton')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();

    release?.();
    await waitFor(() => expect(screen.getByRole('table')).toBeInTheDocument());
  });

  it('says where the first item comes from when the menu is empty', async () => {
    serve({ items: () => json([]) });
    renderScreen();

    expect(await screen.findByTestId('data-table-empty')).toHaveTextContent(
      /Nothing on the menu yet/i,
    );
    expect(screen.getByTestId('low-stock-empty')).toHaveTextContent(/The shelves are stocked/i);
  });

  it('banners a failed read without emptying the screen', async () => {
    serve({
      items: () => json({ error: { code: 'UNKNOWN', message: 'boom', traceId: 't-9' } }, 500),
    });
    renderScreen();

    expect(await screen.findByTestId('inventory-error')).toBeInTheDocument();
    expect(screen.getByTestId('equipment-register')).toBeInTheDocument();
  });

  it('renders a 403 as the access notice, not as an error', async () => {
    serve({
      items: () =>
        json({ error: { code: 'FORBIDDEN', message: 'no', traceId: 't-1' } }, 403),
    });
    renderScreen();

    expect(await screen.findByTestId('access-notice')).toHaveTextContent(/Inventory/);
    expect(screen.queryByTestId('inventory-screen')).not.toBeInTheDocument();
  });
});
