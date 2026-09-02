/**
 * Auth on the wire — frontend/ARCHITECTURE.md §4.4: "401 → one silent refresh
 * → hard logout", and docs/api-contract.md §1 (15-minute access token, rotating
 * refresh cookie).
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  ApiError,
  api,
  configureApi,
  forgetSession,
  getAccessToken,
  logout,
  setAccessToken,
} from '@/lib/api';

const fetchMock = vi.fn();
const onLogout = vi.fn();

function ok(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}

function unauthorized() {
  return new Response(
    JSON.stringify({ error: { code: 'UNAUTHORIZED', message: 'Token expired' } }),
    { status: 401, headers: { 'Content-Type': 'application/json' } },
  );
}

/** URL and headers of the n-th fetch. */
function callOf(index: number): { url: string; init: RequestInit; headers: Headers } {
  const [url, init] = fetchMock.mock.calls[index] as [string, RequestInit];
  return { url, init, headers: new Headers(init.headers) };
}

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock);
  fetchMock.mockReset();
  onLogout.mockReset();
  forgetSession();
  configureApi({ onLogout });
  setAccessToken('old-token');
});

afterEach(() => {
  vi.unstubAllGlobals();
  configureApi({});
});

describe('the bearer token', () => {
  it('rides on every authenticated call, with the refresh cookie', async () => {
    fetchMock.mockResolvedValueOnce(ok([{ id: 1 }]));
    await api.get('/stations');

    const { headers, init } = callOf(0);
    expect(headers.get('Authorization')).toBe('Bearer old-token');
    expect(init.credentials).toBe('include');
  });

  it('is left off an anonymous call — login has no token yet', async () => {
    fetchMock.mockResolvedValueOnce(ok({ accessToken: 'fresh' }));
    await api.post('/auth/login', { staffId: 1, pin: '1234' }, { anonymous: true });

    expect(callOf(0).headers.get('Authorization')).toBeNull();
  });
});

describe('401 → one silent refresh → logout', () => {
  it('refreshes once and replays the original call with the new token', async () => {
    fetchMock
      .mockResolvedValueOnce(unauthorized())
      .mockResolvedValueOnce(ok({ accessToken: 'new-token', tokenType: 'Bearer' }))
      .mockResolvedValueOnce(ok([{ id: 1, name: 'PS5-01' }]));

    const stations = await api.get<{ id: number }[]>('/stations');

    expect(stations).toEqual([{ id: 1, name: 'PS5-01' }]);
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(callOf(1).url).toContain('/auth/refresh');
    expect(callOf(2).headers.get('Authorization')).toBe('Bearer new-token');
    expect(getAccessToken()).toBe('new-token');
    expect(onLogout).not.toHaveBeenCalled();
  });

  it('replays a money call under the very same Idempotency-Key', async () => {
    fetchMock
      .mockResolvedValueOnce(unauthorized())
      .mockResolvedValueOnce(ok({ accessToken: 'new-token' }))
      .mockResolvedValueOnce(ok({ transactionId: 9 }));

    await api.post('/payments', { total: 500 }, { intent: 'settle:session:41' });

    expect(callOf(0).headers.get('Idempotency-Key')).toBe(
      callOf(2).headers.get('Idempotency-Key'),
    );
  });

  it('logs out when the refresh itself is refused — and never retries twice', async () => {
    fetchMock
      .mockResolvedValueOnce(unauthorized())
      .mockResolvedValueOnce(new Response('', { status: 401 }));

    const error = (await api.get('/stations').catch((e: unknown) => e)) as ApiError;

    expect(error.code).toBe('UNAUTHORIZED');
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(getAccessToken()).toBeNull();
    expect(onLogout).toHaveBeenCalledTimes(1);
  });

  it('logs out when the replayed call is refused as well', async () => {
    fetchMock
      .mockResolvedValueOnce(unauthorized())
      .mockResolvedValueOnce(ok({ accessToken: 'new-token' }))
      .mockResolvedValueOnce(unauthorized());

    const error = (await api.get('/stations').catch((e: unknown) => e)) as ApiError;

    expect(error.code).toBe('UNAUTHORIZED');
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(getAccessToken()).toBeNull();
    expect(onLogout).toHaveBeenCalledTimes(1);
  });

  it('logs out when a refresh answers 200 with nothing usable in it', async () => {
    fetchMock.mockResolvedValueOnce(unauthorized()).mockResolvedValueOnce(ok({}));

    const error = (await api.get('/stations').catch((e: unknown) => e)) as ApiError;

    expect(error.code).toBe('UNAUTHORIZED');
    expect(onLogout).toHaveBeenCalledTimes(1);
  });

  it('does not try to refresh a failed login — that 401 is a wrong PIN', async () => {
    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify({ error: { code: 'UNAUTHORIZED', message: 'Wrong PIN' } }), {
        status: 401,
        headers: { 'Content-Type': 'application/json' },
      }),
    );

    const error = (await api
      .post('/auth/login', { staffId: 1, pin: '0000' }, { anonymous: true })
      .catch((e: unknown) => e)) as ApiError;

    expect(error.message).toBe('Wrong PIN');
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(onLogout).not.toHaveBeenCalled();
  });

  it('refreshes once for a burst of calls that all expire together', async () => {
    fetchMock
      .mockResolvedValueOnce(unauthorized())
      .mockResolvedValueOnce(unauthorized())
      .mockResolvedValueOnce(ok({ accessToken: 'new-token' }))
      .mockImplementation(async () => ok([]));

    await Promise.all([api.get('/stations'), api.get('/items')]);

    const refreshCalls = fetchMock.mock.calls.filter(([url]) =>
      String(url).includes('/auth/refresh'),
    );
    expect(refreshCalls).toHaveLength(1);
  });

  it('keeps a 403 as a permission notice — it is not a stale token', async () => {
    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify({ error: { code: 'FORBIDDEN', message: 'Admin only' } }), {
        status: 403,
        headers: { 'Content-Type': 'application/json' },
      }),
    );

    const error = (await api.get('/reports').catch((e: unknown) => e)) as ApiError;

    expect(error.code).toBe('FORBIDDEN');
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(onLogout).not.toHaveBeenCalled();
  });
});

describe('sign-out', () => {
  it('drops the token locally even with the backend down', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'));

    await logout();

    expect(getAccessToken()).toBeNull();
    expect(onLogout).toHaveBeenCalledTimes(1);
  });
});
