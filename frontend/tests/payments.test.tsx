/**
 * S4 settle — split payment, `POST /payments`, and the 80 mm ticket preview
 * (design.md S4 settle + P1/P5/P6, api-contract.md "Billing & payments").
 *
 * State-table assertions, not snapshots. Six things are pinned here, and five
 * of them are the same idea from different angles: **the settle path never
 * moves ahead of the server.**
 *
 *  - the splits must sum to what is due before the button will fire, which is
 *    `SPLIT_MISMATCH` caught a beat early;
 *  - when the server answers `SPLIT_MISMATCH` anyway, the bill is *exactly* as
 *    it was — lines, member, redemption and the typed tender amounts — with a
 *    notice above it (design.md §1, S4: "settle failure keeps bill intact");
 *  - a bKash or Nagad row with no TrxID cannot be sent (`PAYMENT_REF_REQUIRED`);
 *  - success draws the server's stored render, never a receipt of our own
 *    (invariant §5.6), and shows the tokens it issued;
 *  - nothing local is written while the call is in flight, and nothing local is
 *    written when it fails.
 */

import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClientProvider } from '@tanstack/react-query';
import type { QueryClient } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PosScreen } from '@/components/domain/pos-screen';
import { PaymentSplit, remainderLabel } from '@/components/domain/payment-split';
import { ReceiptPreview, previewState } from '@/components/domain/receipt-preview';
import {
  draftAmount,
  effectiveSplits,
  setSplitAmount,
  setSplitRef,
  settleBody,
  splitDraft,
  toggleSplitMethod,
  validateSplits,
  type PaymentSplitDraft,
} from '@/features/payments/schemas';
import { isSettledJob, printFailureNotice, renderLines } from '@/features/printing/use-print-job';
import { billTotals, pointsEarned } from '@/features/pos/bill-math';
import { EMPTY_DRAFT, resetPosStore, useAppStore, type Item } from '@/features/pos/bill-store';
import { makeQueryClient } from '@/lib/query-client';
import { forgetSession } from '@/lib/api';
import type { Pricing } from '@/features/pos/queries';
import type { Station } from '@/features/sessions/queries';
import type { Tournament } from '@/features/tournaments/queries';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn(), prefetch: vi.fn(), refresh: vi.fn() }),
  usePathname: () => '/pos',
  useSearchParams: () => new URLSearchParams(),
}));

const NOW = '2026-09-02T12:00:00Z';
/** The venue day (Asia/Dhaka) `NOW` falls in — what a fresh token is dated. */
const TODAY = '2026-09-02';

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

const RUNNING_PS5: Station = {
  id: 1,
  name: 'Nexus',
  consoleType: 'PS5',
  floorState: 'RUNNING',
  status: 'AVAILABLE',
  session: { id: 41, blocks: 4, paidBlocks: 2, remainingSeconds: 3600, state: 'RUNNING' },
};

const PRICING: Pricing[] = [
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

/** The 48-column text the server stored — the only receipt this app draws. */
const RECEIPT_TEXT = [
  '            GAMER\'S DEN            ',
  '     Jaleshwaritola, Bogura        ',
  '-----------------------------------',
  'TXN            GD-2609-047',
  'STATION        Nexus (PS5)',
  '-----------------------------------',
  'GAMING 2x30M               160',
  'TOTAL                      160',
  'CASH                       160',
].join('\n');

/* --------------------------------------------------------------- harness */

const fetchMock = vi.fn();

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', Date: new Date(NOW).toUTCString() },
  });
}

function conflict(code: string, message: string, details?: Record<string, unknown>) {
  return json({ error: { code, message, details, traceId: 't-1' } }, 409);
}

type SettleAnswer = {
  transactionId?: number;
  publicId?: string;
  printJobId?: number;
  entryTokens?: string[];
  queueTokens?: { queueEntryId: number; tokenNo: number; tokenDate: string }[];
};

const RECEIPT: SettleAnswer = {
  transactionId: 900,
  publicId: 'GD-2609-047',
  printJobId: 77,
};

type Backend = {
  bill?: ReturnType<typeof bill>;
  routes?: Record<string, (init?: RequestInit) => Promise<Response> | Response>;
  jobStatus?: string;
  jobError?: string;
  renderText?: string;
};

/** Every request the screen makes, so a test can prove one was *not* made. */
const calls: { method: string; path: string; body: unknown; key: string | null }[] = [];

function serve(backend: Backend = {}) {
  const {
    bill: sessionBill = bill(),
    routes = {},
    jobStatus = 'DONE',
    jobError,
    renderText = RECEIPT_TEXT,
  } = backend;

  fetchMock.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input.toString();
    const [rawPath = '', search = ''] = url.replace(/^.*\/api\/v1/, '').split('?');
    const method = (init?.method ?? 'GET').toUpperCase();
    const key = `${method} ${rawPath}`;

    calls.push({
      method,
      path: rawPath,
      body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined,
      key: new Headers(init?.headers).get('Idempotency-Key'),
    });

    const override = routes[key];
    if (override) return override(init);

    if (rawPath === '/items') return json([COKE]);
    if (rawPath === '/stations') return json([RUNNING_PS5]);
    if (rawPath === '/tournaments') return json([OPEN_CUP]);
    if (rawPath === '/pricing') return json(PRICING);
    if (rawPath === '/play-queue') return json([]);
    if (rawPath === '/members') {
      const q = new URLSearchParams(search).get('q')?.toLowerCase() ?? '';
      const hits = q === '' ? [] : [RAFI].filter((m) => m.name.toLowerCase().includes(q));
      return json({ content: hits, page: 0, size: 6, totalElements: hits.length });
    }
    if (rawPath.endsWith('/bill')) return json(sessionBill);
    if (rawPath === '/carts') return json({ id: 900, type: 'COUNTER', lines: [], total: 0 });
    if (rawPath === '/payments') return json(RECEIPT, 201);
    if (rawPath.endsWith('/render')) {
      return json({ id: 77, type: 'RECEIPT', columns: 48, text: renderText, bytes: 512 });
    }
    if (rawPath.startsWith('/print-jobs/')) {
      return json({ id: 77, type: 'RECEIPT', refId: 900, status: jobStatus, attempts: 1, error: jobError });
    }
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
  calls.length = 0;
  resetPosStore();
  forgetSession();
  serve();
});

afterEach(() => {
  vi.unstubAllGlobals();
  client?.clear();
});

/** A station bill with ৳160 of unbilled blocks on it, ready to settle. */
async function openStationBill(user: ReturnType<typeof userEvent.setup>) {
  renderPos();
  await screen.findByTestId('bill-panel');
  await user.click(screen.getByRole('radio', { name: /station/i }));
  await waitFor(() => expect(screen.getByTestId('bill-due')).toHaveTextContent('৳160'));
}

function settleCalls() {
  return calls.filter((call) => call.method === 'POST' && call.path === '/payments');
}

/* ------------------------------------------------------- the split, pure */

describe('the split adds up before it is ever sent', () => {
  it('tenders the whole bill in cash until the operator says otherwise', () => {
    const rows = effectiveSplits([], 340);
    expect(rows).toEqual([{ method: 'CASH', amount: '340', paymentRef: '' }]);
    // Derived, not seeded: the row follows the bill as items go on it.
    expect(effectiveSplits([], 420)[0].amount).toBe('420');
  });

  it('gives a second method whatever the first has not covered', () => {
    let splits = setSplitAmount([], 'CASH', '200', 500);
    splits = toggleSplitMethod(splits, 'BKASH', 500);
    expect(splits.map((row) => [row.method, row.amount])).toEqual([
      ['CASH', '200'],
      ['BKASH', '300'],
    ]);
    expect(validateSplits(splits, 500).balanced).toBe(true);
  });

  it('hands a removed row’s amount back rather than leaving the bill short', () => {
    let splits = setSplitAmount([], 'CASH', '200', 500);
    splits = toggleSplitMethod(splits, 'NAGAD', 500);
    splits = toggleSplitMethod(splits, 'NAGAD', 500);
    expect(splits.map((row) => [row.method, row.amount])).toEqual([['CASH', '500']]);
    expect(validateSplits(splits, 500).balanced).toBe(true);
  });

  it('says which way it is out, and by how much', () => {
    const short = validateSplits([splitDraft('CASH', 300)], 500);
    expect(short.remainder).toBe(200);
    expect(short.balanced).toBe(false);
    expect(remainderLabel(short)).toBe('Left to tender');

    const over = validateSplits([splitDraft('CASH', 600)], 500);
    expect(over.remainder).toBe(-100);
    expect(remainderLabel(over)).toBe('Over-tendered');

    const exact = validateSplits([splitDraft('CASH', 500)], 500);
    expect(exact.balanced).toBe(true);
    expect(exact.ok).toBe(true);
    expect(remainderLabel(exact)).toBe('Tendered');
  });

  it('refuses an amount that is not whole taka, keeping the text as typed', () => {
    const splits = setSplitAmount([], 'CASH', '12.50', 500);
    expect(draftAmount(splits[0])).toBeNull();
    const issues = validateSplits(splits, 500);
    expect(issues.unreadable).toEqual(['CASH']);
    expect(issues.ok).toBe(false);
    // The operator's characters survive the verdict (§4.4).
    expect(splits[0].amount).toBe('12.50');
  });

  it('holds an MFS row until it carries a TrxID (PAYMENT_REF_REQUIRED)', () => {
    let splits = setSplitAmount([], 'CASH', '0', 500);
    splits = toggleSplitMethod(splits, 'BKASH', 500);

    const missing = validateSplits(splits, 500);
    expect(missing.balanced).toBe(true); // the money adds up …
    expect(missing.missingRef).toEqual(['BKASH']); // … but the proof does not exist
    expect(missing.ok).toBe(false);

    const withRef = setSplitRef(splits, 'BKASH', '8XK21QW7', 500);
    expect(validateSplits(withRef, 500).ok).toBe(true);
  });

  it('asks for no TrxID on an MFS row carrying nothing', () => {
    // A method toggled on and then zeroed is not a payment to evidence.
    const splits = setSplitAmount(
      toggleSplitMethod(setSplitAmount([], 'CASH', '500', 500), 'NAGAD', 500),
      'NAGAD',
      '0',
      500,
    );
    expect(validateSplits(splits, 500).missingRef).toEqual([]);
  });

  it('keeps a wallet row inside the balance the bill quoted', () => {
    const splits = [splitDraft('WALLET', 600)];
    expect(validateSplits(splits, 600, 500).walletOver).toBe(true);
    expect(validateSplits(splits, 600, 500).ok).toBe(false);
    // The bill's quote is the floor; the binding check happens again under the
    // member's row lock, because another terminal may have spent it since.
    expect(validateSplits(splits, 600, 700).ok).toBe(true);
  });
});

/* ----------------------------------------------------------- the request */

describe('the POST /payments body', () => {
  it('drops zero rows, because payment_splits refuses them', () => {
    const splits: PaymentSplitDraft[] = [splitDraft('CASH', 160), splitDraft('BKASH', 0)];
    expect(settleBody({ sessionId: 41, splits, due: 160 }).splits).toEqual([
      { method: 'CASH', amount: 160, paymentRef: undefined },
    ]);
  });

  it('sends no tenders at all when points paid the whole bill', () => {
    const body = settleBody({ sessionId: 41, splits: [], due: 0, redeemPoints: 160 });
    expect(body.splits).toEqual([]);
    expect(body.redeemPoints).toBe(160);
  });

  it('expands a quantity into one stub per player', () => {
    const body = settleBody({
      cartId: null,
      sessionId: null,
      splits: [splitDraft('CASH', 560)],
      due: 560,
      entries: [{ tournamentId: 5, qty: 2 }],
      tickets: [{ consoleType: 'PS4', blocks: 2, qty: 1 }],
      playerName: 'Rafiul Karim',
    });
    // Two entries are two players with two seeds — not a line with a quantity.
    expect(body.tournamentEntries).toEqual([
      { tournamentId: 5, playerName: 'Rafiul Karim' },
      { tournamentId: 5, playerName: 'Rafiul Karim' },
    ]);
    expect(body.playTickets).toEqual([
      { consoleType: 'PS4', blocks: 2, playerName: 'Rafiul Karim' },
    ]);
    // A walk-up has neither a seat nor a basket.
    expect(body.target).toEqual({});
  });

  it('names one target, never two', () => {
    expect(settleBody({ sessionId: 41, cartId: 900, splits: [], due: 0 }).target).toEqual({
      sessionId: 41,
    });
    expect(settleBody({ cartId: 900, splits: [], due: 0 }).target).toEqual({ cartId: 900 });
  });
});

/* ---------------------------------------------------------- the loyalty */

describe('the loyalty line P1 prints', () => {
  it('earns floor(due / 20) of what was actually paid', () => {
    expect(pointsEarned(400)).toBe(20);
    expect(pointsEarned(19)).toBe(0);
    expect(pointsEarned(0)).toBe(0);
  });

  it('earns on the post-discount total, so points cannot re-earn themselves', () => {
    const totals = billTotals(
      { ...EMPTY_DRAFT, memberId: 7, memberPoints: 320, redeemPoints: 100 },
      { gamingDue: 400, tournamentDue: 0, prepaidCredit: 0, memberPoints: 320 },
    );
    expect(totals.due).toBe(300);
    expect(totals.pointsEarned).toBe(15); // not 20
  });

  it('earns nothing with no member to earn them', () => {
    const totals = billTotals(EMPTY_DRAFT, {
      gamingDue: 400,
      tournamentDue: 0,
      prepaidCredit: 0,
      memberPoints: 0,
    });
    expect(totals.pointsEarned).toBe(0);
  });
});

/* ---------------------------------------------------- the panel, rendered */

describe('the split panel', () => {
  it('will not let the bill be settled while the tender is short', async () => {
    const user = userEvent.setup();
    await openStationBill(user);

    const cash = screen.getByLabelText('Cash');
    await user.clear(cash);
    await user.type(cash, '100');

    const remainder = screen.getByTestId('split-remainder');
    expect(remainder).toHaveTextContent('Left to tender');
    expect(remainder).toHaveTextContent('৳60');
    expect(screen.getByTestId('settle')).toBeDisabled();

    // Nothing was sent — the guard is before the request, not after it.
    expect(settleCalls()).toHaveLength(0);
  });

  it('holds the settle until a bKash row has its TrxID, then releases it', async () => {
    const user = userEvent.setup();
    await openStationBill(user);

    await user.clear(screen.getByLabelText('Cash'));
    await user.type(screen.getByLabelText('Cash'), '60');
    await user.click(screen.getByTestId('split-method-BKASH'));

    // The money adds up; the proof does not exist yet.
    expect(screen.getByTestId('split-remainder')).toHaveAttribute('data-balanced', 'true');
    expect(screen.getByTestId('settle')).toBeDisabled();
    expect(
      within(screen.getByTestId('split-row-BKASH')).getByText('Enter the bKash/Nagad TrxID.'),
    ).toBeInTheDocument();

    await user.type(screen.getByLabelText('bKash TrxID'), '8XK21QW7');

    await waitFor(() => expect(screen.getByTestId('settle')).toBeEnabled());
    await user.click(screen.getByTestId('settle'));

    await waitFor(() => expect(settleCalls()).toHaveLength(1));
    expect(settleCalls()[0].body).toMatchObject({
      target: { sessionId: 41 },
      splits: [
        { method: 'CASH', amount: 60 },
        { method: 'BKASH', amount: 100, paymentRef: '8XK21QW7' },
      ],
    });
  });

  it('offers the wallet only when there is a member on a station bill to draw from', async () => {
    const user = userEvent.setup();
    await openStationBill(user);

    expect(screen.getByTestId('split-method-WALLET')).toBeDisabled();

    await user.type(screen.getByLabelText(/search name or phone/i), 'Rafi');
    await user.click(await screen.findByTestId('member-result'));

    await waitFor(() => expect(screen.getByTestId('split-method-WALLET')).toBeEnabled());
  });
});

/* --------------------------------------------------- failure keeps the bill */

describe('a refused settle leaves the bill exactly as it was', () => {
  it('renders SPLIT_MISMATCH over an untouched bill, and sends nothing else', async () => {
    serve({
      routes: {
        'POST /payments': () =>
          conflict('SPLIT_MISMATCH', 'The tenders come to 160 BDT but 240 is due', {
            expected: 240,
            provided: 160,
          }),
      },
    });

    const user = userEvent.setup();
    await openStationBill(user);
    useAppStore.getState().addTicket({ consoleType: 'PS4', blocks: 2, price: 100 });
    await waitFor(() => expect(screen.getByTestId('bill-due')).toHaveTextContent('৳260'));

    const before = useAppStore.getState().draft;
    await user.click(screen.getByTestId('settle'));

    const notice = await screen.findByTestId('bill-notice');
    expect(notice).toHaveTextContent('The split does not add up to the amount due');
    expect(notice).toHaveTextContent('the bill is unchanged');

    // The bill itself: every line, every figure, still there.
    expect(screen.getByTestId('bill-due')).toHaveTextContent('৳260');
    expect(screen.getByTestId('cart-line')).toHaveAttribute('data-kind', 'ticket');
    expect(useAppStore.getState().draft).toEqual(before);

    // And no consolation write went out behind the failure.
    expect(settleCalls()).toHaveLength(1);
    expect(calls.filter((call) => call.method !== 'GET' && call.path !== '/payments')).toEqual([]);
  });

  it('keeps the typed tender amounts and the TrxID after a refusal', async () => {
    serve({
      routes: {
        'POST /payments': () => conflict('WALLET_INSUFFICIENT', 'The wallet holds 20 BDT'),
      },
    });

    const user = userEvent.setup();
    await openStationBill(user);

    await user.clear(screen.getByLabelText('Cash'));
    await user.type(screen.getByLabelText('Cash'), '60');
    await user.click(screen.getByTestId('split-method-NAGAD'));
    await user.type(screen.getByLabelText('Nagad TrxID'), 'NGD-77123');

    await user.click(screen.getByTestId('settle'));

    expect(await screen.findByTestId('bill-notice')).toHaveTextContent(
      'Not enough wallet balance',
    );
    // An error never destroys entered data (§4.4).
    expect(screen.getByLabelText('Cash')).toHaveValue('60');
    expect(screen.getByLabelText('Nagad')).toHaveValue('100');
    expect(screen.getByLabelText('Nagad TrxID')).toHaveValue('NGD-77123');
    expect(screen.getByTestId('settle')).toBeEnabled();
  });

  it('retries the same intent under the same Idempotency-Key', async () => {
    let attempt = 0;
    serve({
      routes: {
        'POST /payments': () => {
          attempt += 1;
          return attempt === 1 ? json({ error: { code: 'UNKNOWN', message: 'boom' } }, 503) : json(RECEIPT, 201);
        },
      },
    });

    const user = userEvent.setup();
    await openStationBill(user);

    await user.click(screen.getByTestId('settle'));
    await screen.findByTestId('bill-notice');

    await user.click(screen.getByTestId('settle'));
    await waitFor(() => expect(settleCalls()).toHaveLength(2));

    // One operator intent, one key — which is what makes the second press safe.
    const [first, second] = settleCalls();
    expect(first.key).toBeTruthy();
    expect(second.key).toBe(first.key);
  });

  it('never writes anything locally while the settle is in flight', async () => {
    let release: () => void = () => {};
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    serve({
      routes: {
        'POST /payments': async () => {
          await gate;
          return json(RECEIPT, 201);
        },
      },
    });

    const user = userEvent.setup();
    await openStationBill(user);
    useAppStore.getState().addTicket({ consoleType: 'PS4', blocks: 2, price: 100 });
    await waitFor(() => expect(screen.getByTestId('bill-due')).toHaveTextContent('৳260'));

    const before = useAppStore.getState().draft;
    await user.click(screen.getByTestId('settle'));

    // In flight: the bill is untouched, the queue has not gained a token, and
    // no receipt has been invented (§5.3 — payments are never optimistic).
    await waitFor(() => expect(screen.getByTestId('settle')).toHaveAttribute('data-loading'));
    expect(useAppStore.getState().draft).toEqual(before);
    expect(screen.getByTestId('bill-due')).toHaveTextContent('৳260');
    expect(screen.queryByTestId('receipt-render')).not.toBeInTheDocument();
    expect(screen.getByTestId('receipt-preview')).toHaveAttribute('data-state', 'rendering');
    expect(screen.queryByTestId('queue-tokens')).not.toBeInTheDocument();

    release();

    // Only once the server has answered does the bill clear.
    await waitFor(() => expect(useAppStore.getState().draft.tickets).toHaveLength(0));
  });
});

/* ------------------------------------------------------ success and paper */

describe('a settled bill draws the server’s ticket', () => {
  it('clears the bill and renders the stored 48-column artifact', async () => {
    const user = userEvent.setup();
    await openStationBill(user);

    await user.click(screen.getByTestId('settle'));

    const paper = await screen.findByTestId('receipt-render');
    // The render is fetched from the job the settle created …
    expect(calls.some((call) => call.path === '/print-jobs/77/render')).toBe(true);
    // … and drawn verbatim. Nothing here composes a receipt (invariant §5.6).
    expect(paper).toHaveTextContent("GAMER'S DEN");
    expect(paper).toHaveTextContent('GD-2609-047');
    expect(paper).toHaveAttribute('data-columns', '48');
    expect(screen.getByTestId('receipt-preview')).toHaveAttribute('data-state', 'ready');

    expect(screen.getByTestId('print-job-status')).toHaveAttribute('data-status', 'DONE');
    expect(useAppStore.getState().draft).toEqual(EMPTY_DRAFT);
  });

  it('shows the queue tokens a play-ticket sale issued', async () => {
    serve({
      routes: {
        'POST /payments': () =>
          json(
            {
              ...RECEIPT,
              queueTokens: [
                { queueEntryId: 5, tokenNo: 4, tokenDate: TODAY },
                { queueEntryId: 6, tokenNo: 5, tokenDate: TODAY },
              ],
            },
            201,
          ),
      },
    });

    const user = userEvent.setup();
    renderPos();
    await screen.findByTestId('bill-panel');
    useAppStore.getState().addTicket({ consoleType: 'PS4', blocks: 2, price: 100 }, 2);
    await waitFor(() => expect(screen.getByTestId('bill-due')).toHaveTextContent('৳200'));

    await user.click(screen.getByTestId('settle'));

    const tokens = await screen.findByTestId('queue-tokens');
    expect(within(tokens).getByText('TOKEN #04')).toBeInTheDocument();
    expect(within(tokens).getByText('TOKEN #05')).toBeInTheDocument();
    // Issued today, so no date qualifier (§5.12).
    expect(within(tokens).queryByText(TODAY)).not.toBeInTheDocument();

    expect(settleCalls()[0].body).toMatchObject({
      target: {},
      playTickets: [
        { consoleType: 'PS4', blocks: 2, playerName: 'Walk-in guest' },
        { consoleType: 'PS4', blocks: 2, playerName: 'Walk-in guest' },
      ],
    });
  });

  it('shows the entry tokens a tournament sale issued', async () => {
    serve({
      routes: {
        'POST /payments': () => json({ ...RECEIPT, entryTokens: ['qr-8f2a', 'qr-91cd'] }, 201),
      },
    });

    const user = userEvent.setup();
    renderPos();
    await screen.findByTestId('bill-panel');
    useAppStore.getState().addEntry({ tournamentId: 5, name: 'Friday FIFA Cup', fee: 200 }, 2);
    useAppStore.getState().setPlayerName('Imran Kabir');
    await waitFor(() => expect(screen.getByTestId('bill-due')).toHaveTextContent('৳400'));

    await user.click(screen.getByTestId('settle'));

    const stubs = await screen.findByTestId('entry-tokens');
    expect(within(stubs).getAllByTestId('entry-token')).toHaveLength(2);
    expect(within(stubs).getByText('qr-8f2a')).toBeInTheDocument();

    expect(settleCalls()[0].body).toMatchObject({
      tournamentEntries: [
        { tournamentId: 5, playerName: 'Imran Kabir' },
        { tournamentId: 5, playerName: 'Imran Kabir' },
      ],
    });
  });

  it('dates a token from a previous day rather than letting two #04s read alike', async () => {
    render(
      <QueryClientProvider client={makeQueryClient()}>
        <ReceiptPreview
          printJobId={null}
          today={TODAY}
          result={{ queueTokens: [{ queueEntryId: 5, tokenNo: 4, tokenDate: '2026-09-01' }] }}
        />
      </QueryClientProvider>,
    );
    expect(screen.getByText('2026-09-01')).toBeInTheDocument();
  });

  it('names the thing to fix when the printer refused the job', async () => {
    serve({ jobStatus: 'FAILED', jobError: 'PAPER_OUT' });

    const user = userEvent.setup();
    await openStationBill(user);
    await user.click(screen.getByTestId('settle'));

    const status = await screen.findByTestId('print-job-status');
    await waitFor(() => expect(status).toHaveAttribute('data-status', 'FAILED'));
    expect(status).toHaveTextContent('out of paper');
    // The sale still happened — the paper is a separate problem.
    expect(screen.getByTestId('receipt-render')).toBeInTheDocument();
  });
});

/* ---------------------------------------------------- the preview, in bits */

describe('ReceiptPreview states', () => {
  it('is rendering until there are lines, then ready, and failed on a bad read', () => {
    expect(previewState(true, false, 0)).toBe('rendering');
    expect(previewState(false, false, 0)).toBe('rendering');
    expect(previewState(false, false, 9)).toBe('ready');
    expect(previewState(false, true, 0)).toBe('failed');
  });

  it('splits the stored text on its own newlines and never re-wraps it', () => {
    expect(renderLines({ text: 'A\r\nB\nC' })).toEqual(['A', 'B', 'C']);
    expect(renderLines(undefined)).toEqual([]);
    expect(renderLines({ text: '' })).toEqual([]);
  });

  it('stops polling a job that has stopped moving', () => {
    expect(isSettledJob({ status: 'QUEUED' })).toBe(false);
    expect(isSettledJob({ status: 'PRINTING' })).toBe(false);
    expect(isSettledJob({ status: 'DONE' })).toBe(true);
    expect(isSettledJob({ status: 'FAILED' })).toBe(true);
  });

  it('names each printer failure rather than saying “try again”', () => {
    expect(printFailureNotice({ error: 'COVER_OPEN' })).toContain('cover is open');
    expect(printFailureNotice({ error: 'OFFLINE' })).toContain('offline');
    expect(printFailureNotice({})).toBe('This ticket did not print.');
  });

  it('says nothing has printed yet before there is a job', () => {
    render(
      <QueryClientProvider client={makeQueryClient()}>
        <ReceiptPreview printJobId={null} />
      </QueryClientProvider>,
    );
    expect(screen.getByTestId('receipt-idle')).toBeInTheDocument();
    expect(screen.queryByTestId('print-job-status')).not.toBeInTheDocument();
  });
});

/* ------------------------------------------------- the panel in isolation */

describe('PaymentSplit as a component', () => {
  it('hands the whole next array up and holds no state of its own', async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    const splits: PaymentSplitDraft[] = [splitDraft('CASH', 500)];

    render(
      <PaymentSplit
        due={500}
        splits={splits}
        onChange={onChange}
        issues={validateSplits(splits, 500)}
      />,
    );

    await user.click(screen.getByTestId('split-method-NAGAD'));
    expect(onChange).toHaveBeenCalledWith([
      { method: 'CASH', amount: '500', paymentRef: '' },
      { method: 'NAGAD', amount: '0', paymentRef: '' },
    ]);
  });
});
