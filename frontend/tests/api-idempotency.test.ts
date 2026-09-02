/**
 * Idempotency — docs/api-contract.md §1, frontend/ARCHITECTURE.md §5.4:
 * "Idempotency-Key (uuid) attached by lib/api.ts to every mutating money/print
 * call; key is per user intent, **reused on retry**."
 *
 * A retried settle, booking or print may never double-charge, double-register
 * or double-print — which is exactly what reusing the key buys.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  ApiError,
  api,
  apiRequest,
  currentIdempotencyKey,
  forgetSession,
  requiresIdempotencyKey,
  setAccessToken,
} from '@/lib/api';

const fetchMock = vi.fn();

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function ok(body: unknown, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json', ...headers },
  });
}

function envelope(status: number, code: string) {
  return new Response(JSON.stringify({ error: { code, message: code } }), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/** The `Idempotency-Key` sent on the n-th fetch of this test. */
function keyOf(call: number): string | null {
  const [, init] = fetchMock.mock.calls[call] as [string, RequestInit];
  return new Headers(init.headers).get('Idempotency-Key');
}

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock);
  fetchMock.mockReset();
  forgetSession();
  setAccessToken('access-token');
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('which routes are guarded', () => {
  it('matches the backend IdempotencyPolicy line for line', () => {
    expect(requiresIdempotencyKey('POST', '/payments')).toBe(true);
    expect(requiresIdempotencyKey('POST', '/print-jobs')).toBe(true);
    expect(requiresIdempotencyKey('POST', '/sessions/41/blocks')).toBe(true);
    expect(requiresIdempotencyKey('POST', '/members/7/wallet/topup')).toBe(true);
    expect(requiresIdempotencyKey('POST', '/members/7/wallet/redeem-points')).toBe(true);
    expect(requiresIdempotencyKey('POST', '/tournaments/3/entries')).toBe(true);
    expect(requiresIdempotencyKey('POST', '/bookings')).toBe(true);
    expect(requiresIdempotencyKey('POST', '/bookings/12/cancel')).toBe(true);
    expect(requiresIdempotencyKey('POST', '/play-tickets')).toBe(true);
  });

  it('leaves everything else alone', () => {
    expect(requiresIdempotencyKey('GET', '/bookings')).toBe(false);
    expect(requiresIdempotencyKey('POST', '/sessions')).toBe(false);
    expect(requiresIdempotencyKey('POST', '/bookings/12/check-in')).toBe(false);
    expect(requiresIdempotencyKey('POST', '/play-queue/5/seat')).toBe(false);
    expect(requiresIdempotencyKey('PUT', '/booking-settings')).toBe(false);
  });

  it('ignores a query string and a trailing slash, as the backend does', () => {
    expect(requiresIdempotencyKey('POST', '/bookings/')).toBe(true);
    expect(requiresIdempotencyKey('POST', '/payments?dryRun=false')).toBe(true);
  });
});

describe('the key on the wire', () => {
  it('sends a UUID key on a guarded call and none elsewhere', async () => {
    fetchMock.mockResolvedValueOnce(ok({ transactionId: 9 }));
    await api.post('/payments', { total: 500 }, { intent: 'settle:session:41' });
    expect(keyOf(0)).toMatch(UUID);

    fetchMock.mockResolvedValueOnce(ok({ id: 12 }));
    await api.post('/sessions', { stationId: 2 });
    expect(keyOf(1)).toBeNull();
  });

  it('refuses to send a guarded call with no intent rather than earning a 400', async () => {
    await expect(api.post('/payments', { total: 500 })).rejects.toThrow(/requires an Idempotency-Key/);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('surfaces the server’s replay flag', async () => {
    fetchMock.mockResolvedValueOnce(ok({ bookingId: 3 }, { 'Idempotency-Replayed': 'true' }));

    const response = await apiRequest('/bookings', {
      method: 'POST',
      body: { stationId: 1 },
      intent: 'booking:create:form-1',
    });

    expect(response.replayed).toBe(true);
  });
});

describe('one key per intent, reused on retry', () => {
  it('reuses the key when the first attempt fails and the operator retries', async () => {
    const intent = 'settle:session:41';
    const body = { target: { sessionId: 41 }, splits: [{ method: 'CASH', amount: 500 }] };

    // Attempt 1: the venue box blips.
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'));
    const failure = await api.post('/payments', body, { intent }).catch((e: unknown) => e);
    expect((failure as ApiError).code).toBe('NETWORK_ERROR');

    const firstKey = keyOf(0);
    expect(firstKey).toMatch(UUID);
    // The intent is still open, so the key is still held.
    expect(currentIdempotencyKey(intent)).toBe(firstKey);

    // Attempt 2: same intent, same key — the server replays instead of charging twice.
    fetchMock.mockResolvedValueOnce(ok({ transactionId: 9 }, { 'Idempotency-Replayed': 'true' }));
    await api.post('/payments', body, { intent });

    expect(keyOf(1)).toBe(firstKey);
  });

  it('holds the key across a 503 and a 409 the retry can clear', async () => {
    const intent = 'print:job:receipt-9';

    fetchMock.mockResolvedValueOnce(envelope(503, 'PRINTER_UNAVAILABLE'));
    await api.post('/print-jobs', { type: 'RECEIPT' }, { intent }).catch(() => undefined);
    const key = keyOf(0);

    fetchMock.mockResolvedValueOnce(envelope(409, 'CONFLICT'));
    await api.post('/print-jobs', { type: 'RECEIPT' }, { intent }).catch(() => undefined);
    expect(keyOf(1)).toBe(key);

    fetchMock.mockResolvedValueOnce(ok({ id: 9 }));
    await api.post('/print-jobs', { type: 'RECEIPT' }, { intent });
    expect(keyOf(2)).toBe(key);
  });

  it('mints a fresh key once the intent has succeeded', async () => {
    const intent = 'booking:cancel:12';

    fetchMock.mockResolvedValueOnce(ok({ refundTransactionId: 5 }));
    await api.post('/bookings/12/cancel', {}, { intent });
    const firstKey = keyOf(0);
    expect(currentIdempotencyKey(intent)).toBeUndefined();

    fetchMock.mockResolvedValueOnce(ok({ refundTransactionId: 6 }));
    await api.post('/bookings/12/cancel', {}, { intent });

    expect(keyOf(1)).toMatch(UUID);
    expect(keyOf(1)).not.toBe(firstKey);
  });

  it('gives two intents two different keys', async () => {
    fetchMock.mockResolvedValueOnce(ok({ id: 1 }));
    await api.post('/bookings', { stationId: 1 }, { intent: 'booking:create:form-1' });

    fetchMock.mockResolvedValueOnce(ok({ id: 2 }));
    await api.post('/bookings', { stationId: 2 }, { intent: 'booking:create:form-2' });

    expect(keyOf(0)).not.toBe(keyOf(1));
  });

  it('drops the key when the payload itself has to change', async () => {
    const intent = 'booking:create:form-3';

    // The server rejected the body — retrying it unchanged is pointless, and
    // retrying a corrected body under the same key is a 409.
    fetchMock.mockResolvedValueOnce(envelope(400, 'VALIDATION_FAILED'));
    await api.post('/bookings', { blocks: 0 }, { intent }).catch(() => undefined);
    expect(currentIdempotencyKey(intent)).toBeUndefined();

    fetchMock.mockResolvedValueOnce(ok({ id: 4 }));
    await api.post('/bookings', { blocks: 2 }, { intent });
    expect(keyOf(1)).not.toBe(keyOf(0));
  });

  it('drops the key the server says was already used for something else', async () => {
    const intent = 'settle:session:42';

    fetchMock.mockResolvedValueOnce(envelope(409, 'IDEMPOTENCY_REPLAY'));
    await api.post('/payments', { total: 100 }, { intent }).catch(() => undefined);

    expect(currentIdempotencyKey(intent)).toBeUndefined();
  });

  it('forgets every open key at sign-out', async () => {
    fetchMock.mockResolvedValueOnce(envelope(503, 'SYNC_UNAVAILABLE'));
    await api.post('/payments', { total: 1 }, { intent: 'settle:session:99' }).catch(() => undefined);
    expect(currentIdempotencyKey('settle:session:99')).toBeDefined();

    forgetSession();
    expect(currentIdempotencyKey('settle:session:99')).toBeUndefined();
  });
});
