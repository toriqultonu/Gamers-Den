/**
 * The error envelope, parsed once — docs/api-contract.md §1,
 * backend/ARCHITECTURE.md §4.4, frontend/ARCHITECTURE.md §4.4.
 *
 * The screens switch on `error.code`, so the canonical spellings and the
 * status-only fallbacks are what this file pins down.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  ApiError,
  DOMAIN_ERROR_CODES,
  ERROR_NOTICES,
  STANDARD_ERROR_CODES,
  api,
  errorNotice,
  forgetSession,
  hasErrorCode,
  isApiError,
  setAccessToken,
} from '@/lib/api';

const fetchMock = vi.fn();

function envelope(
  status: number,
  code: string,
  message = 'Human-readable',
  details?: Record<string, unknown>,
) {
  return new Response(
    JSON.stringify({ error: { code, message, details, traceId: 'trace-42' } }),
    { status, headers: { 'Content-Type': 'application/json' } },
  );
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

describe('the canonical code lists', () => {
  it('carries every standard code from api-contract.md §1', () => {
    expect([...STANDARD_ERROR_CODES]).toEqual([
      'VALIDATION_FAILED',
      'UNAUTHORIZED',
      'FORBIDDEN',
      'NOT_FOUND',
      'CONFLICT',
      'IDEMPOTENCY_REPLAY',
      'LOCKED_PIN',
      'RATE_LIMITED',
      'PRINTER_UNAVAILABLE',
      'SYNC_UNAVAILABLE',
    ]);
  });

  it('carries every domain 409 from backend/ARCHITECTURE.md §4.4', () => {
    expect([...DOMAIN_ERROR_CODES]).toEqual([
      'STATION_BUSY',
      'STATION_RESERVED',
      'STATION_IN_USE',
      'BLOCKS_CONSUMED',
      'NO_BLOCKS',
      'SESSION_HAS_BALANCE',
      'OUT_OF_STOCK',
      'DUPLICATE_NAME',
      'DUPLICATE_PHONE',
      'INSUFFICIENT_POINTS',
      'SPLIT_MISMATCH',
      'WALLET_INSUFFICIENT',
      'PAYMENT_REF_REQUIRED',
      'SHIFT_ALREADY_OPEN',
      'STAFF_ON_SHIFT',
      'TOURNAMENT_FULL',
      'TOURNAMENT_NOT_OPEN',
      'NOT_ENOUGH_PLAYERS',
      'NO_FREE_CONSOLE',
      'ALREADY_CHECKED_IN',
      'PREBOOKING_DISABLED',
      'CANCEL_CUTOFF_PASSED',
      'CONSOLE_TYPE_MISMATCH',
    ]);
  });
});

describe('envelope → typed error', () => {
  it.each([
    ['CANCEL_CUTOFF_PASSED', 409],
    ['PREBOOKING_DISABLED', 409],
    ['CONSOLE_TYPE_MISMATCH', 409],
    ['SESSION_HAS_BALANCE', 409],
    ['BLOCKS_CONSUMED', 409],
    ['STATION_RESERVED', 409],
    ['SPLIT_MISMATCH', 409],
    ['NO_FREE_CONSOLE', 409],
    ['ALREADY_CHECKED_IN', 409],
    ['LOCKED_PIN', 423],
    ['PRINTER_UNAVAILABLE', 503],
  ] as const)('maps %s straight through', async (code, status) => {
    fetchMock.mockResolvedValueOnce(envelope(status, code));

    const error = await api.get('/bookings').catch((e: unknown) => e);

    expect(isApiError(error)).toBe(true);
    expect((error as ApiError).code).toBe(code);
    expect((error as ApiError).status).toBe(status);
    expect((error as ApiError).traceId).toBe('trace-42');
    expect(hasErrorCode(error, code)).toBe(true);
    expect(hasErrorCode(error, 'NOT_FOUND')).toBe(false);
  });

  it('keeps the details a form needs to mark its fields', async () => {
    fetchMock.mockResolvedValueOnce(
      envelope(400, 'VALIDATION_FAILED', 'Invalid request', { field: 'phone' }),
    );

    const error = (await api.post('/bookings/1/check-in').catch((e: unknown) => e)) as ApiError;

    expect(error.code).toBe('VALIDATION_FAILED');
    expect(error.details).toEqual({ field: 'phone' });
  });

  it('passes an unknown code through verbatim rather than flattening it', async () => {
    fetchMock.mockResolvedValueOnce(envelope(409, 'SOME_NEW_CODE', 'Newer backend'));

    const error = (await api.get('/stations').catch((e: unknown) => e)) as ApiError;

    expect(error.code).toBe('SOME_NEW_CODE');
    expect(error.message).toBe('Newer backend');
  });

  it('falls back to the status when there is no envelope at all', async () => {
    fetchMock.mockResolvedValueOnce(new Response('<html>502</html>', { status: 403 }));

    const error = (await api.get('/reports').catch((e: unknown) => e)) as ApiError;

    expect(error.code).toBe('FORBIDDEN');
    expect(error.status).toBe(403);
  });

  it('reports an unreachable venue box as NETWORK_ERROR, not a TypeError', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'));

    const error = (await api.get('/stations').catch((e: unknown) => e)) as ApiError;

    expect(isApiError(error)).toBe(true);
    expect(error.code).toBe('NETWORK_ERROR');
    expect(error.status).toBe(0);
  });
});

describe('codes → notices (design.md §1 state tables)', () => {
  it('explains the three booking/queue refusals in the operator’s words', () => {
    expect(ERROR_NOTICES.CANCEL_CUTOFF_PASSED).toMatch(/cancel/i);
    expect(ERROR_NOTICES.PREBOOKING_DISABLED).toMatch(/pre-booking/i);
    expect(ERROR_NOTICES.CONSOLE_TYPE_MISMATCH).toMatch(/console type/i);
  });

  it('has a notice for every domain code', () => {
    for (const code of DOMAIN_ERROR_CODES) {
      expect(ERROR_NOTICES[code], code).toBeTruthy();
    }
  });

  it('falls back to the server’s own message for a code it does not know', async () => {
    fetchMock.mockResolvedValueOnce(envelope(409, 'SOME_NEW_CODE', 'A newer refusal'));
    const error = await api.get('/stations').catch((e: unknown) => e);

    expect(errorNotice(error)).toBe('A newer refusal');
    expect(errorNotice(new Error('boom'))).toBe('Something went wrong.');
  });

  it('uses the house copy when it has one', async () => {
    fetchMock.mockResolvedValueOnce(envelope(409, 'SESSION_HAS_BALANCE', 'balance due'));
    const error = await api.post('/sessions/1/end').catch((e: unknown) => e);

    expect(errorNotice(error)).toBe(ERROR_NOTICES.SESSION_HAS_BALANCE);
  });
});
