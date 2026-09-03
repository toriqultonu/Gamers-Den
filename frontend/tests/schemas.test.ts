/**
 * Feature schemas — the client-side half of the rules that carry a domain code:
 * `PAYMENT_REF_REQUIRED`, `SPLIT_MISMATCH`, `CANCEL_CUTOFF_PASSED`,
 * `CONSOLE_TYPE_MISMATCH` (docs/bookings.md §2–3, api-contract.md §2).
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  createBookingSchema,
  isCancellable,
  updateBookingSettingsSchema,
} from '@/features/bookings/schemas';
import {
  canSeatOn,
  sellPlayTicketSchema,
  waitingInTokenOrder,
} from '@/features/queue/schemas';
import { blocksSchema, clockSnapshot, hasBalance } from '@/features/sessions/schemas';
import { settleRequestSchema, splitsBalance, splitTotal } from '@/features/payments/schemas';
import { noteServerTime, remainingSecondsNow, resetServerTime, serverNow } from '@/lib/time';

const START = '2026-09-02T18:00:00+06:00';

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(Date.parse('2026-09-02T12:00:00Z'));
  resetServerTime();
});

afterEach(() => {
  vi.useRealTimers();
  resetServerTime();
});

describe('booking form', () => {
  const valid = {
    stationId: 1,
    name: 'Rafi',
    phone: '01700000000',
    startAt: START,
    blocks: 4,
    method: 'CASH' as const,
  };

  it('accepts a complete cash booking', () => {
    expect(createBookingSchema.safeParse(valid).success).toBe(true);
  });

  it('demands a TrxID on bKash and Nagad (PAYMENT_REF_REQUIRED)', () => {
    const missing = createBookingSchema.safeParse({ ...valid, method: 'BKASH' });
    expect(missing.success).toBe(false);
    expect(missing.error?.issues[0]?.path).toEqual(['paymentRef']);

    expect(
      createBookingSchema.safeParse({ ...valid, method: 'NAGAD', paymentRef: 'TRX123' }).success,
    ).toBe(true);
  });

  it('needs a name and at least one 30-minute block', () => {
    expect(createBookingSchema.safeParse({ ...valid, name: '  ' }).success).toBe(false);
    expect(createBookingSchema.safeParse({ ...valid, blocks: 0 }).success).toBe(false);
    expect(createBookingSchema.safeParse({ ...valid, blocks: 49 }).success).toBe(false);
  });

  it('rejects a start time with no offset — the venue books in +06:00', () => {
    expect(createBookingSchema.safeParse({ ...valid, startAt: '2026-09-02 18:00' }).success).toBe(
      false,
    );
  });

  it('keeps the admin settings inside the DDL bounds', () => {
    expect(
      updateBookingSettingsSchema.safeParse({
        enabled: true,
        packageFee: 100,
        cancelCutoffHours: 2,
      }).success,
    ).toBe(true);
    expect(
      updateBookingSettingsSchema.safeParse({
        enabled: true,
        packageFee: -1,
        cancelCutoffHours: 2,
      }).success,
    ).toBe(false);
  });
});

describe('cancellation cutoff (the CANCEL_CUTOFF_PASSED twin)', () => {
  const booking = { status: 'PAID' as const, startAt: START, cutoffHours: 2 };

  it('is open more than the cutoff before the start', () => {
    noteServerTime('2026-09-02T15:00:00+06:00'); // 3 h before
    expect(isCancellable(booking, serverNow())).toBe(true);
  });

  it('locks inside the window', () => {
    noteServerTime('2026-09-02T16:30:00+06:00'); // 1.5 h before
    expect(isCancellable(booking, serverNow())).toBe(false);
  });

  it('locks exactly on the cutoff — "at least cutoff hours before"', () => {
    noteServerTime('2026-09-02T16:00:00+06:00');
    expect(isCancellable(booking, serverNow())).toBe(false);
  });

  it('is closed for anything already checked in or cancelled', () => {
    noteServerTime('2026-09-02T10:00:00+06:00');
    expect(isCancellable({ ...booking, status: 'ARRIVED' }, serverNow())).toBe(false);
    expect(isCancellable({ ...booking, status: 'CANCELLED' }, serverNow())).toBe(false);
  });
});

describe('play tickets and the queue', () => {
  it('sells a ticket for a console type and a length', () => {
    expect(
      sellPlayTicketSchema.safeParse({
        consoleType: 'PS5',
        blocks: 2,
        playerName: 'Walk-in guest',
        method: 'CASH',
      }).success,
    ).toBe(true);

    expect(
      sellPlayTicketSchema.safeParse({ consoleType: 'XBOX', blocks: 2, method: 'CASH' }).success,
    ).toBe(false);
  });

  it('refuses to seat a token on the wrong console type (CONSOLE_TYPE_MISMATCH)', () => {
    const ticket = { consoleType: 'PS5', status: 'WAITING' };
    expect(canSeatOn(ticket, { consoleType: 'PS5', floorState: 'FREE' })).toBe(true);
    expect(canSeatOn(ticket, { consoleType: 'PS4', floorState: 'FREE' })).toBe(false);
  });

  it('refuses to seat on a console that is not free (STATION_BUSY)', () => {
    const ticket = { consoleType: 'PS5', status: 'WAITING' };
    expect(canSeatOn(ticket, { consoleType: 'PS5', floorState: 'RUNNING' })).toBe(false);
    expect(canSeatOn(ticket, { consoleType: 'PS5', floorState: 'RESERVED' })).toBe(false);
  });

  it('lists waiting tokens in token order, seated ones not at all', () => {
    const entries = [
      { id: 3, tokenNo: 7, tokenDate: '2026-09-02', status: 'WAITING' },
      { id: 1, tokenNo: 4, tokenDate: '2026-09-02', status: 'WAITING' },
      { id: 2, tokenNo: 5, tokenDate: '2026-09-02', status: 'SEATED' },
      { id: 4, tokenNo: 9, tokenDate: '2026-09-01', status: 'WAITING' },
    ];

    expect(waitingInTokenOrder(entries).map((entry) => entry.id)).toEqual([4, 1, 3]);
  });
});

describe('sessions', () => {
  it('buys and returns time one block at a time', () => {
    expect(blocksSchema.safeParse({ delta: 1 }).success).toBe(true);
    expect(blocksSchema.safeParse({ delta: -1 }).success).toBe(true);
    expect(blocksSchema.safeParse({ delta: 2 }).success).toBe(false);
    expect(blocksSchema.safeParse({ delta: 0 }).success).toBe(false);
  });

  it('turns the server reading into a clock only a RUNNING session drains', () => {
    const asOf = '2026-09-02T12:00:00Z';
    noteServerTime(asOf);

    const running = clockSnapshot(
      { remainingSeconds: 1800, state: 'RUNNING', serverTime: asOf },
      serverNow(),
    );
    const paused = clockSnapshot(
      { remainingSeconds: 1800, state: 'PAUSED', serverTime: asOf },
      serverNow(),
    );

    vi.setSystemTime(Date.parse('2026-09-02T12:05:00Z'));
    expect(remainingSecondsNow(running)).toBe(1500);
    expect(remainingSecondsNow(paused)).toBe(1800);
  });

  it('blocks End session while anything is outstanding (SESSION_HAS_BALANCE)', () => {
    expect(hasBalance({ netOutstanding: 240 })).toBe(true);
    // A seated prepaid booking carries paid blocks — nothing is due.
    expect(hasBalance({ netOutstanding: 0 })).toBe(false);
  });
});

describe('settle', () => {
  const target = { sessionId: 41 };

  it('takes a split tender that adds up', () => {
    const parsed = settleRequestSchema.safeParse({
      target,
      splits: [
        { method: 'CASH', amount: 300 },
        { method: 'BKASH', amount: 200, paymentRef: 'TRX99' },
      ],
    });
    expect(parsed.success).toBe(true);
    expect(splitTotal(parsed.data!.splits)).toBe(500);
  });

  it('mirrors SPLIT_MISMATCH before the bill is ever sent', () => {
    const splits = [{ amount: 300 }, { amount: 150 }];
    expect(splitsBalance(splits, 500)).toBe(false);
    expect(splitsBalance(splits, 450)).toBe(true);
    // Redeemed points count toward what is due.
    expect(splitsBalance(splits, 550, 100)).toBe(true);
  });

  it('settles a session or a counter cart, never both', () => {
    const splits = [{ method: 'CASH' as const, amount: 100 }];
    expect(settleRequestSchema.safeParse({ target: { cartId: 8 }, splits }).success).toBe(true);
    expect(
      settleRequestSchema.safeParse({ target: { sessionId: 1, cartId: 8 }, splits }).success,
    ).toBe(false);
    // Neither, with nothing else on the bill, is nothing to settle.
    expect(settleRequestSchema.safeParse({ target: {}, splits }).success).toBe(false);
  });

  it('lets a walk-up ticket sale settle against an empty target', () => {
    // "A walk-up buying only tickets: no seat, no basket" — PaymentService
    // resolves an empty target when playTickets[] or tournamentEntries[] carry
    // the sale (billing/domain/PaymentService.java, `walkUp`).
    const splits = [{ method: 'CASH' as const, amount: 160 }];
    expect(
      settleRequestSchema.safeParse({
        target: {},
        splits,
        playTickets: [{ consoleType: 'PS5', blocks: 2 }],
      }).success,
    ).toBe(true);
    expect(
      settleRequestSchema.safeParse({
        target: {},
        splits,
        tournamentEntries: [{ tournamentId: 5, playerName: 'Rafiul Karim' }],
      }).success,
    ).toBe(true);
  });

  it('takes no tenders at all on a bill points paid for outright', () => {
    // payment_splits carries CHECK (amount <> 0): "paid nothing" is an empty
    // list, never a zero row (billing/domain/Settlement.java).
    expect(
      settleRequestSchema.safeParse({ target: { sessionId: 41 }, splits: [], redeemPoints: 160 })
        .success,
    ).toBe(true);
  });

  it('demands the TrxID on an MFS split', () => {
    const parsed = settleRequestSchema.safeParse({
      target,
      splits: [{ method: 'BKASH', amount: 500 }],
    });
    expect(parsed.success).toBe(false);
  });
});
