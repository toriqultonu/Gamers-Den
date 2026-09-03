/**
 * S4 — Point of sale: menu grid, bill panel, member attach, redemption,
 * tournament entries and play tickets (design.md §1/§2, docs/tournaments.md
 * §5, docs/bookings.md §3).
 *
 * State-table assertions, not snapshots. What is pinned down here is the
 * handful of places the POS is allowed to move ahead of the server, the
 * arithmetic the operator reads off the rail, and the two cards whose
 * enabled/disabled rule is a domain rule rather than a style:
 *
 *  - a cart line is optimistic **and reconciles** to the server's cart, and
 *    rolls back whole on `OUT_OF_STOCK`;
 *  - redemption is capped at min(points, subtotal) and the stepper cannot
 *    offer a rung above the cap;
 *  - a full tournament's card is disabled (the twin of `TOURNAMENT_FULL`);
 *  - a play ticket sells while every console is busy — that is the queue's
 *    whole purpose;
 *  - the player-name field appears only with an entry or a ticket on the bill,
 *    auto-fills from an attached member, and falls back to "Walk-in guest".
 */

import { render, renderHook, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClientProvider } from '@tanstack/react-query';
import type { QueryClient } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PosScreen, billableStations, openTournaments, visibleItems } from '@/components/domain/pos-screen';
import { MenuItemCard } from '@/components/domain/menu-item-card';
import { CartLine } from '@/components/domain/cart-line';
import { RedeemStepper } from '@/components/domain/redeem-stepper';
import { memberSearchVariant } from '@/components/domain/member-search';
import { useSetCartLine, applyLine } from '@/features/pos/mutations';
import { playTicketProducts, blockPriceOf } from '@/features/pos/queries';
import {
  EMPTY_DRAFT,
  billTarget,
  resetPosStore,
  useAppStore,
  type BillDraft,
  type Cart,
  type Item,
} from '@/features/pos/bill-store';
import {
  billTotals,
  effectivePlayerName,
  maxRedeemable,
  needsPlayerName,
  playerNameValue,
  redeemSteps,
  ticketPrice,
} from '@/features/pos/bill-math';
import { makeQueryClient } from '@/lib/query-client';
import { forgetSession } from '@/lib/api';
import type { Station } from '@/features/sessions/queries';
import type { Tournament } from '@/features/tournaments/queries';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn(), prefetch: vi.fn(), refresh: vi.fn() }),
  usePathname: () => '/pos',
  useSearchParams: () => new URLSearchParams(),
}));

const NOW = '2026-09-02T12:00:00Z';

/* ------------------------------------------------------------- fixtures */

const COKE: Item = {
  id: 11,
  name: 'Coca-Cola 250ml',
  category: 'BEVERAGE',
  price: 40,
  stock: 24,
  reorderAt: 6,
  available: 24,
  lowStock: false,
  outOfStock: false,
  active: true,
};

const BURGER: Item = {
  id: 12,
  name: 'Chicken Burger',
  category: 'FOOD',
  price: 180,
  stock: 2,
  reorderAt: 4,
  available: 2,
  lowStock: true,
  outOfStock: false,
  active: true,
};

const CRISPS: Item = {
  id: 13,
  name: 'Potato Crisps',
  category: 'SNACK',
  price: 30,
  stock: 0,
  reorderAt: 4,
  available: 0,
  lowStock: true,
  outOfStock: true,
  active: true,
};

/** Both consoles busy — the state a play ticket has to be sellable in. */
const BUSY_PS5: Station = {
  id: 1,
  name: 'Nexus',
  consoleType: 'PS5',
  floorState: 'RUNNING',
  status: 'AVAILABLE',
  session: { id: 41, blocks: 4, paidBlocks: 2, remainingSeconds: 3600, state: 'RUNNING' },
};

const BUSY_PS4: Station = {
  id: 2,
  name: 'Titan',
  consoleType: 'PS4',
  floorState: 'RUNNING',
  status: 'AVAILABLE',
  session: { id: 42, blocks: 2, paidBlocks: 0, remainingSeconds: 1800, state: 'RUNNING' },
};

const PRICING = [
  { consoleType: 'PS5', perHour: 120, perHalfHour: 80, currentBlockPrice: 80 },
  { consoleType: 'PS4', perHour: 80, perHalfHour: 50, currentBlockPrice: 50 },
];

const OPEN_CUP: Tournament = {
  id: 5,
  name: 'Friday FIFA Cup',
  game: 'FIFA 25',
  status: 'OPEN',
  entryFee: 200,
  maxPlayers: 8,
  entries: 6,
  slotsLeft: 2,
  prizePool: 1200,
  matchDurationMin: 20,
  cadence: 'WEEKLY',
};

const FULL_CUP: Tournament = {
  ...OPEN_CUP,
  id: 6,
  name: 'Tekken Showdown',
  entries: 8,
  slotsLeft: 0,
};

const RAFI = { id: 7, name: 'Rafiul Karim', phone: '+880 1711-000111', points: 320, wallet: 500 };

function bill(overrides: Record<string, unknown> = {}) {
  return {
    sessionId: 41,
    stationId: 1,
    billableBlocks: 2,
    gamingDue: 160,
    fnbDue: 0,
    tournamentDue: 0,
    prepaidCredit: 0,
    pointsRedeemable: 0,
    netTotal: 160,
    serverTime: NOW,
    settled: false,
    ...overrides,
  };
}

/** A server cart — the shape every optimistic patch reconciles against. */
function cart(lines: { itemId: number; name: string; unitPrice: number; qty: number }[]): Cart {
  const rows = lines.map((line) => ({ ...line, lineTotal: line.unitPrice * line.qty }));
  return {
    id: 900,
    type: 'COUNTER',
    settled: false,
    lines: rows,
    total: rows.reduce((sum, row) => sum + row.lineTotal, 0),
  };
}

/* --------------------------------------------------------------- harness */

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

type Backend = {
  items?: Item[];
  stations?: Station[];
  tournaments?: Tournament[];
  members?: { id: number; name: string; phone: string; points: number; wallet: number }[];
  bill?: ReturnType<typeof bill>;
  routes?: Record<string, () => Promise<Response> | Response>;
  itemsStatus?: number;
};

function serve(backend: Backend = {}) {
  const {
    items = [COKE, BURGER, CRISPS],
    stations = [BUSY_PS5, BUSY_PS4],
    tournaments = [OPEN_CUP, FULL_CUP],
    members = [RAFI],
    bill: sessionBill = bill(),
    routes = {},
    itemsStatus = 200,
  } = backend;

  fetchMock.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input.toString();
    const [rawPath = '', search = ''] = url.replace(/^.*\/api\/v1/, '').split('?');
    const key = `${(init?.method ?? 'GET').toUpperCase()} ${rawPath}`;

    const override = routes[key];
    if (override) return override();

    if (rawPath === '/items') {
      return itemsStatus === 200
        ? json(items)
        : json({ error: { code: 'FORBIDDEN', message: 'no', traceId: 't' } }, itemsStatus);
    }
    if (rawPath === '/stations') return json(stations);
    if (rawPath === '/tournaments') return json(tournaments);
    if (rawPath === '/pricing') return json(PRICING);
    if (rawPath === '/play-queue') return json([]);
    if (rawPath === '/members') {
      const q = new URLSearchParams(search).get('q')?.toLowerCase() ?? '';
      const digits = q.replace(/\D/g, '');
      const hits = members.filter(
        (member) =>
          member.name.toLowerCase().includes(q) ||
          (digits !== '' && member.phone.replace(/\D/g, '').includes(digits)),
      );
      return json({ content: q === '' ? [] : hits, page: 0, size: 6, totalElements: hits.length });
    }
    if (rawPath.endsWith('/bill')) return json(sessionBill);
    if (rawPath === '/carts') return json({ id: 900, type: 'COUNTER', lines: [], total: 0 });
    return json({});
  });
}

let client: QueryClient;

function renderPos() {
  client = makeQueryClient();
  client.setDefaultOptions({ queries: { retry: false } });
  return render(
    <QueryClientProvider client={client}>
      <PosScreen />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock);
  fetchMock.mockReset();
  resetPosStore();
  forgetSession();
  serve();
});

afterEach(() => {
  vi.unstubAllGlobals();
  client?.clear();
});

/** The bill rail, once the screen has painted. */
async function billPanel() {
  return screen.findByTestId('bill-panel');
}

function cardNamed(name: string | RegExp) {
  return screen
    .getAllByTestId('menu-card')
    .find((card) => within(card).queryByText(name) !== null);
}

/* ------------------------------------------------------- cart: optimistic */

describe('the cart moves first and the server reconciles it', () => {
  it('paints the line before the server answers, then takes the server’s cart', async () => {
    let release: (() => void) | null = null;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });

    serve({
      routes: {
        // The server prices this line at 45, not the menu's 40 — the point of
        // reconciling rather than trusting the optimistic arithmetic.
        'PUT /carts/900/lines': async () => {
          await gate;
          return json(cart([{ itemId: 11, name: 'Coca-Cola 250ml', unitPrice: 45, qty: 1 }]));
        },
      },
    });

    const user = userEvent.setup();
    renderPos();

    const card = await waitFor(() => {
      const found = cardNamed('Coca-Cola 250ml');
      expect(found).toBeDefined();
      return found!;
    });

    await user.click(card);

    // Optimistic: the line and the menu's price are on the bill immediately,
    // while the PUT is still in flight.
    const line = await screen.findByTestId('cart-line');
    expect(within(line).getByTestId('cart-line-qty')).toHaveTextContent('1');
    expect(within(line).getByText('৳40')).toBeInTheDocument();

    release?.();

    // Reconciled: the server's snapshot replaces the guess outright.
    await waitFor(() => {
      expect(within(screen.getByTestId('cart-line')).getByText('৳45')).toBeInTheDocument();
    });
    expect(await screen.findByTestId('bill-subtotal')).toHaveTextContent('৳45');
  });

  it('removes the line when − is pressed at one, and asks the server for qty 0', async () => {
    const puts: unknown[] = [];
    serve({
      routes: {
        'PUT /carts/900/lines': () => json(cart([])),
      },
    });
    const originalImpl = fetchMock.getMockImplementation()!;
    fetchMock.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      if ((init?.method ?? 'GET').toUpperCase() === 'PUT') puts.push(JSON.parse(String(init?.body)));
      return originalImpl(input, init);
    });

    const user = userEvent.setup();
    renderPos();

    // Seed a line without a round trip, so the assertion is about the remove.
    useAppStore
      .getState()
      .setCart(cart([{ itemId: 11, name: 'Coca-Cola 250ml', unitPrice: 40, qty: 1 }]));

    const line = await screen.findByTestId('cart-line');
    await user.click(within(line).getByRole('button', { name: /remove coca-cola/i }));

    await waitFor(() => expect(screen.queryByTestId('cart-line')).not.toBeInTheDocument());
    expect(puts).toEqual([{ itemId: 11, qty: 0 }]);
  });

  it('rolls the whole cart back on OUT_OF_STOCK and says why', async () => {
    serve({
      routes: {
        'PUT /carts/900/lines': () => conflict('OUT_OF_STOCK', 'only 2 left'),
      },
    });

    const user = userEvent.setup();
    renderPos();

    const card = await waitFor(() => {
      const found = cardNamed('Chicken Burger');
      expect(found).toBeDefined();
      return found!;
    });

    await user.click(card);

    await waitFor(() => {
      expect(screen.getByTestId('bill-notice')).toHaveTextContent('Not enough stock left');
    });
    expect(screen.queryByTestId('cart-line')).not.toBeInTheDocument();
    expect(await screen.findByTestId('bill-subtotal')).toHaveTextContent('৳0');
  });

  it('rolls back to the previous cart, not to empty', async () => {
    const previous = cart([{ itemId: 11, name: 'Coca-Cola 250ml', unitPrice: 40, qty: 2 }]);

    const { result } = renderHook(() => useSetCartLine(), {
      wrapper: ({ children }) => {
        client = makeQueryClient();
        client.setDefaultOptions({ queries: { retry: false } });
        return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
      },
    });

    useAppStore.getState().setCart(previous);
    serve({ routes: { 'PUT /carts/900/lines': () => conflict('OUT_OF_STOCK', 'no') } });

    await expect(result.current.mutateAsync({ item: BURGER, qty: 1 })).rejects.toBeTruthy();

    expect(useAppStore.getState().draft.cart).toEqual(previous);
  });

  it('predicts a cart purely — add, increase, remove', () => {
    const one = applyLine(null, COKE, 1);
    expect(one.total).toBe(40);

    const three = applyLine(one, COKE, 3);
    expect(three.total).toBe(120);
    expect(three.lines).toHaveLength(1);

    const gone = applyLine(three, COKE, 0);
    expect(gone.lines).toHaveLength(0);
    expect(gone.total).toBe(0);
  });

  it('keeps the server’s snapshot price when a line grows', () => {
    const server = cart([{ itemId: 11, name: 'Coca-Cola 250ml', unitPrice: 45, qty: 1 }]);
    expect(applyLine(server, COKE, 2).total).toBe(90);
  });
});

/* ------------------------------------------------------------ redeem cap */

describe('redemption is capped at min(points, subtotal)', () => {
  it('caps the maximum at whichever is smaller', () => {
    expect(maxRedeemable(320, 160)).toBe(160);
    expect(maxRedeemable(80, 160)).toBe(80);
    expect(maxRedeemable(0, 160)).toBe(0);
  });

  it('drops the rungs that pass the cap', () => {
    expect(redeemSteps(500).map((step) => step.label)).toEqual(['None', '100', '200', 'Max 500']);
    expect(redeemSteps(150).map((step) => step.label)).toEqual(['None', '100', 'Max 150']);
    expect(redeemSteps(80).map((step) => step.label)).toEqual(['None', 'Max 80']);
    expect(redeemSteps(0).map((step) => step.label)).toEqual(['None']);
    // 100 exactly: the fixed rung *is* the max, and is not offered twice.
    expect(redeemSteps(100).map((step) => step.label)).toEqual(['None', 'Max 100']);
  });

  it('clamps a stale choice rather than over-discounting', () => {
    const draft: BillDraft = {
      ...EMPTY_DRAFT,
      memberId: 7,
      memberName: 'Rafiul Karim',
      memberPoints: 320,
      redeemPoints: 200,
    };
    // The bill shrank to ৳150 after the choice was made.
    const totals = billTotals(draft, {
      gamingDue: 150,
      tournamentDue: 0,
      prepaidCredit: 0,
      memberPoints: 320,
    });
    expect(totals.maxRedeem).toBe(150);
    expect(totals.redeem).toBe(150);
    expect(totals.due).toBe(0);
  });

  it('offers nothing without a member', () => {
    const totals = billTotals(EMPTY_DRAFT, {
      gamingDue: 400,
      tournamentDue: 0,
      prepaidCredit: 0,
      memberPoints: 900,
    });
    expect(totals.maxRedeem).toBe(0);
    expect(totals.due).toBe(400);
  });

  it('spends points before prepaid credit', () => {
    const draft: BillDraft = {
      ...EMPTY_DRAFT,
      memberId: 7,
      memberName: 'Rafiul Karim',
      memberPoints: 100,
      redeemPoints: 100,
    };
    const totals = billTotals(draft, {
      gamingDue: 300,
      tournamentDue: 0,
      prepaidCredit: 250,
      memberPoints: 100,
    });
    expect(totals.redeem).toBe(100);
    expect(totals.credit).toBe(200); // only what is left to cover
    expect(totals.due).toBe(0);
  });

  it('renders only the rungs the cap allows', async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<RedeemStepper max={150} value={0} onChange={onChange} />);

    expect(screen.getByRole('button', { name: '100' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '200' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Max 150' }));
    expect(onChange).toHaveBeenCalledWith(150);
  });

  it('attaches a member, redeems the max and drops the amount due', async () => {
    const user = userEvent.setup();
    renderPos();

    await billPanel();
    await user.click(screen.getByRole('radio', { name: /counter sale/i }));

    // Nothing to redeem against until the bill has something on it.
    useAppStore.getState().addTicket({ consoleType: 'PS5', blocks: 2, price: 160 });

    await user.type(screen.getByLabelText(/search name or phone/i), 'Rafi');
    const hit = await screen.findByTestId('member-result');
    await user.click(hit);

    // 320 points against a ৳160 bill: the cap is the bill.
    const stepper = await screen.findByTestId('redeem-stepper');
    expect(stepper).toHaveAttribute('data-max', '160');

    await user.click(within(stepper).getByRole('button', { name: 'Max 160' }));
    await waitFor(() => expect(screen.getByTestId('bill-due')).toHaveTextContent('৳0'));
    expect(screen.getByTestId('bill-redeem')).toHaveTextContent('160 pts');
  });
});

/* --------------------------------------------------------- tournament card */

describe('the Tournament category', () => {
  it('disables the card of a full event and leaves the open one sellable', async () => {
    const user = userEvent.setup();
    renderPos();

    await waitFor(() => expect(cardNamed(/Friday FIFA Cup/)).toBeDefined());

    const full = cardNamed(/Tekken Showdown/)!;
    expect(full).toBeDisabled();
    expect(within(full).getByTestId('menu-card-note')).toHaveTextContent('Full');

    const open = cardNamed(/Friday FIFA Cup/)!;
    expect(open).toBeEnabled();
    expect(within(open).getByTestId('menu-card-note')).toHaveTextContent('2 slots left');

    await user.click(open);
    const line = await screen.findByTestId('cart-line');
    expect(line).toHaveAttribute('data-kind', 'entry');
    expect(line).toHaveTextContent('Entry — Friday FIFA Cup');
    expect(await screen.findByTestId('bill-subtotal')).toHaveTextContent('৳200');
  });

  it('clicking a full card sells nothing', async () => {
    const user = userEvent.setup();
    renderPos();

    await waitFor(() => expect(cardNamed(/Tekken Showdown/)).toBeDefined());
    await user.click(cardNamed(/Tekken Showdown/)!);

    expect(screen.queryByTestId('cart-line')).not.toBeInTheDocument();
  });

  it('will not let + oversell the remaining slots', async () => {
    const user = userEvent.setup();
    renderPos();

    await waitFor(() => expect(cardNamed(/Friday FIFA Cup/)).toBeDefined());
    await user.click(cardNamed(/Friday FIFA Cup/)!);

    const line = await screen.findByTestId('cart-line');
    const plus = within(line).getByRole('button', { name: /one more/i });
    await user.click(plus);

    // Two of the two remaining slots — the third is refused client-side, the
    // twin of 409 TOURNAMENT_FULL.
    await waitFor(() =>
      expect(within(screen.getByTestId('cart-line')).getByTestId('cart-line-qty')).toHaveTextContent(
        '2',
      ),
    );
    expect(within(screen.getByTestId('cart-line')).getByRole('button', { name: /one more/i })).toBeDisabled();
  });

  it('lists only OPEN events', () => {
    expect(
      openTournaments([OPEN_CUP, { ...OPEN_CUP, id: 9, status: 'LIVE' }, FULL_CUP]).map((t) => t.id),
    ).toEqual([5, 6]);
  });
});

/* ---------------------------------------------------------- play tickets */

describe('the Play-ticket category', () => {
  it('sells while every console is busy', async () => {
    const user = userEvent.setup();
    renderPos();

    // Both consoles are RUNNING — nothing is free to seat anyone on.
    await waitFor(() => expect(billableStations([BUSY_PS5, BUSY_PS4])).toHaveLength(2));

    const ticket = await waitFor(() => {
      const found = cardNamed(/Play ticket — PS5/);
      expect(found).toBeDefined();
      return found!;
    });
    expect(ticket).toBeEnabled();
    // 2 blocks × the PS5 current block price of 80.
    expect(within(ticket).getByText('৳160')).toBeInTheDocument();

    await user.click(ticket);

    const line = await screen.findByTestId('cart-line');
    expect(line).toHaveAttribute('data-kind', 'ticket');
    expect(line).toHaveTextContent('Play ticket — PS5 · 1 h');
    expect(await screen.findByTestId('bill-subtotal')).toHaveTextContent('৳160');
  });

  it('reprices with the length picker', async () => {
    const user = userEvent.setup();
    renderPos();

    await waitFor(() => expect(cardNamed(/Play ticket — PS4/)).toBeDefined());
    expect(within(cardNamed(/Play ticket — PS4/)!).getByText('৳100')).toBeInTheDocument();

    await user.click(
      within(screen.getByTestId('ticket-length')).getByRole('button', { name: /remove 30 minutes/i }),
    );

    await waitFor(() => {
      expect(cardNamed(/Play ticket — PS4 · 30 min/)).toBeDefined();
    });
    expect(within(cardNamed(/Play ticket — PS4 · 30 min/)!).getByText('৳50')).toBeInTheDocument();
  });

  it('prices a ticket at blocks × the current block rate', () => {
    expect(blockPriceOf(PRICING, 'PS5')).toBe(80);
    expect(ticketPrice(3, 80)).toBe(240);
    expect(playTicketProducts(PRICING, 2)).toEqual([
      { consoleType: 'PS5', blocks: 2, price: 160, priced: true },
      { consoleType: 'PS4', blocks: 2, price: 100, priced: true },
    ]);
  });

  it('is dead until the rate card answers, rather than selling at ৳0', () => {
    expect(playTicketProducts(undefined, 2).every((product) => product.priced)).toBe(false);
  });
});

/* --------------------------------------------------------- player name */

describe('the player-name field', () => {
  it('appears only once an entry or a ticket is on the bill', () => {
    expect(needsPlayerName(EMPTY_DRAFT)).toBe(false);
    expect(
      needsPlayerName({ ...EMPTY_DRAFT, entries: [{ tournamentId: 5, name: 'Cup', fee: 200, qty: 1 }] }),
    ).toBe(true);
    expect(
      needsPlayerName({
        ...EMPTY_DRAFT,
        tickets: [{ consoleType: 'PS5', blocks: 2, price: 160, qty: 1 }],
      }),
    ).toBe(true);
  });

  it('falls back to “Walk-in guest”, then free text, then the member', () => {
    expect(effectivePlayerName(EMPTY_DRAFT)).toBe('Walk-in guest');
    expect(effectivePlayerName({ ...EMPTY_DRAFT, playerName: '  ' })).toBe('Walk-in guest');
    expect(effectivePlayerName({ ...EMPTY_DRAFT, playerName: 'Imran' })).toBe('Imran');
    expect(
      effectivePlayerName({ ...EMPTY_DRAFT, playerName: 'Imran', memberName: 'Rafiul Karim' }),
    ).toBe('Rafiul Karim');
    expect(playerNameValue({ ...EMPTY_DRAFT, playerName: 'Imran', memberName: 'Rafiul' })).toBe(
      'Rafiul',
    );
  });

  it('is absent on a plain food bill and present with a ticket', async () => {
    const user = userEvent.setup();
    renderPos();

    await billPanel();
    expect(screen.queryByTestId('player-name')).not.toBeInTheDocument();

    await user.click(
      await waitFor(() => {
        const found = cardNamed(/Play ticket — PS5/);
        expect(found).toBeDefined();
        return found!;
      }),
    );

    const field = await screen.findByTestId('player-name');
    expect(field).toBeEnabled();
    await user.type(field, 'Imran');
    expect(useAppStore.getState().draft.playerName).toBe('Imran');
  });

  it('auto-fills from the attached member and locks to it', async () => {
    const user = userEvent.setup();
    renderPos();

    await billPanel();
    useAppStore.getState().addTicket({ consoleType: 'PS5', blocks: 2, price: 160 });

    await user.type(screen.getByLabelText(/search name or phone/i), 'Rafi');
    await user.click(await screen.findByTestId('member-result'));

    const field = await screen.findByTestId('player-name');
    await waitFor(() => expect(field).toHaveValue('Rafiul Karim'));
    expect(field).toBeDisabled();

    // Removing the member hands the field back.
    await user.click(
      within(screen.getByTestId('member-search')).getByRole('button', { name: 'Remove' }),
    );
    await waitFor(() => expect(screen.getByTestId('player-name')).toBeEnabled());
  });
});

/* ---------------------------------------------------------- member attach */

describe('MemberSearch', () => {
  it('names its four design.md variants', () => {
    expect(memberSearchVariant(null, '', [])).toBe('collapsed');
    expect(memberSearchVariant(null, 'raf', [RAFI])).toBe('results');
    expect(memberSearchVariant({ id: 7, name: 'Rafiul', points: 0, wallet: 0 }, '', [])).toBe(
      'attached',
    );
    expect(
      memberSearchVariant({ id: 7, name: 'Rafiul', points: 0, wallet: 0, auto: true }, '', []),
    ).toBe('auto-attached');
  });

  it('shows the no-match notice without breaking the bill', async () => {
    const user = userEvent.setup();
    renderPos();

    await billPanel();
    await user.type(screen.getByLabelText(/search name or phone/i), 'zzzz');

    await waitFor(() =>
      expect(screen.getByTestId('member-no-match')).toHaveTextContent('No member matches'),
    );
    expect(screen.getByTestId('bill-panel')).toBeInTheDocument();
  });

  it('auto-attaches the session’s member on a station bill and yields to the operator', async () => {
    const user = userEvent.setup();
    serve({
      bill: bill({ memberId: 7, memberName: 'Rafiul Karim', memberPoints: 320, memberWallet: 500 }),
    });
    renderPos();

    await billPanel();
    await user.click(screen.getByRole('radio', { name: /station/i }));

    const search = await screen.findByTestId('member-search');
    await waitFor(() => expect(search).toHaveAttribute('data-variant', 'auto-attached'));
    expect(within(search).getByTestId('member-auto')).toBeInTheDocument();

    // Removing by hand is sticky: the session's member does not creep back.
    await user.click(within(search).getByRole('button', { name: /remove/i }));
    await waitFor(() =>
      expect(screen.getByTestId('member-search')).toHaveAttribute('data-variant', 'collapsed'),
    );
    expect(useAppStore.getState().draft.memberId).toBeNull();
  });
});

/* -------------------------------------------------------- station vs counter */

describe('station and counter bills', () => {
  it('bills the session’s unbilled blocks in station mode and nothing in counter mode', async () => {
    const user = userEvent.setup();
    renderPos();

    await billPanel();
    await user.click(screen.getByRole('radio', { name: /station/i }));

    await waitFor(() => expect(screen.getByTestId('bill-gaming')).toHaveTextContent('৳160'));
    expect(screen.getByTestId('bill-subtotal')).toHaveTextContent('৳160');

    await user.click(screen.getByRole('radio', { name: /counter sale/i }));
    await waitFor(() => expect(screen.queryByTestId('bill-gaming')).not.toBeInTheDocument());
    expect(screen.getByTestId('bill-subtotal')).toHaveTextContent('৳0');
  });

  it('clears the draft when the bill target changes', () => {
    const store = useAppStore.getState();
    store.setTarget('counter');
    store.addTicket({ consoleType: 'PS5', blocks: 2, price: 160 });
    expect(useAppStore.getState().draft.tickets).toHaveLength(1);

    useAppStore.getState().setTarget('station:41');
    expect(useAppStore.getState().draft.tickets).toHaveLength(0);

    // Re-pointing at the same bill is not a change.
    useAppStore.getState().addTicket({ consoleType: 'PS4', blocks: 1, price: 50 });
    useAppStore.getState().setTarget('station:41');
    expect(useAppStore.getState().draft.tickets).toHaveLength(1);
  });

  it('names a bill the same way from either screen', () => {
    expect(billTarget('counter', 41)).toBe('counter');
    expect(billTarget('station', 41)).toBe('station:41');
    expect(billTarget('station', null)).toBe('station:none');
  });

  it('applies a seated booking’s prepaid credit', async () => {
    serve({ bill: bill({ gamingDue: 0, prepaidCredit: 240, netTotal: 0 }) });
    const user = userEvent.setup();
    renderPos();

    await billPanel();
    await user.click(screen.getByRole('radio', { name: /station/i }));
    useAppStore.getState().addTicket({ consoleType: 'PS4', blocks: 2, price: 100 });

    await waitFor(() => expect(screen.getByTestId('bill-credit')).toHaveTextContent('−৳100'));
    expect(screen.getByTestId('bill-due')).toHaveTextContent('৳0');
  });
});

/* ------------------------------------------------------------ the states */

describe('the five states design.md §1 requires', () => {
  it('renders a skeleton shaped like the grid while the menu loads', async () => {
    serve({ routes: { 'GET /items': () => new Promise(() => {}) as never } });
    renderPos();
    expect(await screen.findByTestId('menu-skeleton')).toBeInTheDocument();
  });

  it('says “Menu is empty” with nothing to sell', async () => {
    serve({ items: [], tournaments: [], routes: { 'GET /pricing': () => json([]) } });
    renderPos();
    // The ticket cards go with the rate card: nothing is priced, nothing sells.
    await waitFor(() => expect(screen.getByTestId('menu-empty')).toHaveTextContent('Menu is empty'));
  });

  it('keeps the bill intact when the menu read fails', async () => {
    serve({ routes: { 'GET /items': () => json({ error: { code: 'UNKNOWN', message: 'boom' } }, 500) } });
    renderPos();

    expect(await screen.findByTestId('pos-error')).toBeInTheDocument();
    expect(screen.getByTestId('bill-panel')).toBeInTheDocument();
  });

  it('renders the access notice on a 403 rather than an empty menu', async () => {
    serve({ itemsStatus: 403 });
    renderPos();
    expect(await screen.findByTestId('access-notice')).toBeInTheDocument();
  });

  it('filters the grid by category', () => {
    expect(visibleItems([COKE, BURGER, CRISPS], 'ALL')).toHaveLength(3);
    expect(visibleItems([COKE, BURGER, CRISPS], 'FOOD')).toEqual([BURGER]);
    expect(visibleItems([COKE, BURGER, CRISPS], 'TOURNAMENT')).toEqual([]);
    expect(visibleItems([COKE, BURGER, CRISPS], 'PLAY_TICKET')).toEqual([]);
  });

  it('disables an out-of-stock card', async () => {
    renderPos();
    await waitFor(() => expect(cardNamed('Potato Crisps')).toBeDefined());
    expect(cardNamed('Potato Crisps')).toBeDisabled();
  });
});

/* -------------------------------------------------- the 1024–1279 collapse */

describe('the ticket column', () => {
  it('collapses behind a Preview button below 1280', async () => {
    const user = userEvent.setup();
    renderPos();

    const column = await screen.findByTestId('ticket-column');
    // Hidden by the media query at 1024–1279 until the operator asks.
    expect(column.className).toContain('max-[1279px]:hidden');
    expect(column).toHaveAttribute('data-open', 'false');

    await user.click(screen.getByTestId('preview-toggle'));

    const opened = screen.getByTestId('ticket-column');
    expect(opened).toHaveAttribute('data-open', 'true');
    expect(opened.className).not.toContain('max-[1279px]:hidden');
    expect(screen.getByTestId('preview-toggle')).toHaveAttribute('aria-expanded', 'true');
  });
});

/* ------------------------------------------------------- the primitives */

describe('MenuItemCard and CartLine', () => {
  it('will not fire when disabled', async () => {
    const onAdd = vi.fn();
    const user = userEvent.setup();
    render(
      <MenuItemCard kicker="Tournament" name="Entry — Cup" price={200} note="Full" disabled onAdd={onAdd} />,
    );
    await user.click(screen.getByTestId('menu-card'));
    expect(onAdd).not.toHaveBeenCalled();
  });

  it('asks for zero rather than one when − is pressed at the floor', async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<CartLine name="Coca-Cola" qty={1} lineTotal={40} onChange={onChange} />);
    await user.click(screen.getByRole('button', { name: /remove coca-cola/i }));
    expect(onChange).toHaveBeenCalledWith(0);
  });
});
