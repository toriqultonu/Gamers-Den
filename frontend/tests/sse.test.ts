/**
 * `GET /events` → the query cache (frontend/ARCHITECTURE.md §4.1, §5.2;
 * api-contract.md, "Live updates & sync").
 *
 * Two claims are worth a test here, and both are about the floor being right
 * rather than about the code being neat:
 *
 *   1. an event lands in the *canonical* key the screens already read — a
 *      handler writing `['station']` instead of `['stations']` is a silently
 *      frozen Floor, and nothing else would catch it;
 *   2. when the stream drops, the 10 s fallback takes over — a dead stream is
 *      silent, so this is the difference between a stale card and a wrong one.
 */

import { QueryClient, QueryObserver } from '@tanstack/react-query';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  LIVE_EVENT_NAMES,
  SseParser,
  applyLiveEvent,
  isLiveEventName,
  startLiveStream,
  type LiveStream,
} from '@/lib/sse';
import { queryKeys } from '@/lib/query-keys';

/* ------------------------------------------------------------------ fixtures */

function station(id: number, floorState: string, extra: Record<string, unknown> = {}) {
  return { id, name: `PS5 #${id}`, consoleType: 'PS5', floorState, ...extra };
}

function queueEntry(tokenNo: number, status = 'WAITING') {
  return { id: tokenNo * 10, tokenNo, tokenDate: '2026-09-03', status, consoleType: 'PS5' };
}

/** A stream we can push frames down, close, or break. */
function fakeStream() {
  const encoder = new TextEncoder();
  let controller!: ReadableStreamDefaultController<Uint8Array>;
  const body = new ReadableStream<Uint8Array>({
    start(c) {
      controller = c;
    },
  });
  const write = (text: string) => controller.enqueue(encoder.encode(text));
  return {
    body,
    send: (event: string, data: unknown) => write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`),
    /** The hub's keep-alive: a comment, carrying no event. */
    ping: () => write(': ping\n\n'),
    close: () => controller.close(),
    fail: () => controller.error(new Error('connection lost')),
  };
}

function streamResponse(body: ReadableStream<Uint8Array>): Response {
  return {
    ok: true,
    status: 200,
    headers: new Headers({ 'Content-Type': 'text/event-stream' }),
    body,
  } as unknown as Response;
}

function client(): QueryClient {
  return new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: 10_000, gcTime: Infinity } },
  });
}

const streams: LiveStream[] = [];

function start(options: Parameters<typeof startLiveStream>[0]): LiveStream {
  const stream = startLiveStream(options);
  streams.push(stream);
  return stream;
}

afterEach(() => {
  while (streams.length) streams.pop()?.close();
});

/* ------------------------------------------------------------- the wire */

describe('the event-stream parser', () => {
  it('reads a frame that arrives in pieces', () => {
    const parser = new SseParser();

    expect(parser.push('event: station-up')).toEqual([]);
    expect(parser.push('date\ndata: {"id":')).toEqual([]);

    expect(parser.push('3}\n\n')).toEqual([
      { event: 'station-update', data: '{"id":3}', id: undefined, retry: undefined },
    ]);
  });

  it('ignores the heartbeat and the opening comment', () => {
    const parser = new SseParser();
    expect(parser.push(':connected\n\n')).toEqual([]);
    expect(parser.push(': ping\n\n')).toEqual([]);
  });

  it('accepts CRLF line endings and joins multi-line data', () => {
    const parser = new SseParser();
    const frames = parser.push('event: alert\r\ndata: {"id":1,\r\ndata: "title":"x"}\r\n\r\n');

    expect(frames).toHaveLength(1);
    expect(frames[0].event).toBe('alert');
    expect(JSON.parse(frames[0].data)).toEqual({ id: 1, title: 'x' });
  });

  it('holds a trailing CR back rather than dispatching half a frame', () => {
    const parser = new SseParser();
    expect(parser.push('event: alert\r')).toEqual([]);
    expect(parser.push('\ndata: {"id":2}\r\n\r\n')).toHaveLength(1);
  });

  it('knows the seven names of the contract and nothing else', () => {
    expect([...LIVE_EVENT_NAMES]).toEqual([
      'station-update',
      'queue-update',
      'booking-update',
      'tournament-update',
      'alert',
      'printer-status',
      'sync-status',
    ]);
    expect(isLiveEventName('station-update')).toBe(true);
    expect(isLiveEventName('message')).toBe(false);
  });
});

/* ------------------------------------------------------- cache writes */

describe('station-update', () => {
  it('replaces the card in ["stations"], in place', () => {
    const cache = client();
    cache.setQueryData(queryKeys.stations.all(), [
      station(1, 'FREE'),
      station(2, 'RUNNING'),
      station(3, 'FREE'),
    ]);

    applyLiveEvent(cache, 'station-update', station(2, 'PAUSED', { session: { id: 41, blocks: 2 } }));

    const rows = cache.getQueryData<ReturnType<typeof station>[]>(queryKeys.stations.all())!;
    expect(rows.map((row) => row.id)).toEqual([1, 2, 3]);
    expect(rows[1].floorState).toBe('PAUSED');
  });

  it('marks the open session and its bill stale — the card carries only a summary', () => {
    const cache = client();
    cache.setQueryData(queryKeys.stations.all(), [station(2, 'RUNNING')]);
    cache.setQueryData(queryKeys.sessions.detail(41), { id: 41, state: 'RUNNING' });
    cache.setQueryData(queryKeys.sessions.bill(41), { total: 500 });

    applyLiveEvent(cache, 'station-update', station(2, 'RUNNING', { session: { id: 41, blocks: 3 } }));

    expect(cache.getQueryState(queryKeys.sessions.detail(41))?.isInvalidated).toBe(true);
    expect(cache.getQueryState(queryKeys.sessions.bill(41))?.isInvalidated).toBe(true);
  });

  it('leaves an unfetched floor alone rather than rendering a floor of one', () => {
    const cache = client();
    applyLiveEvent(cache, 'station-update', station(2, 'RUNNING'));
    expect(cache.getQueryData(queryKeys.stations.all())).toBeUndefined();
  });

  it('ignores a payload with no station id', () => {
    const cache = client();
    cache.setQueryData(queryKeys.stations.all(), [station(1, 'FREE')]);

    applyLiveEvent(cache, 'station-update', { floorState: 'RUNNING' });

    expect(cache.getQueryData<ReturnType<typeof station>[]>(queryKeys.stations.all())).toEqual([
      station(1, 'FREE'),
    ]);
  });
});

describe('queue-update', () => {
  it('writes the whole rail into ["queue"], in the order the server sent it', () => {
    const cache = client();
    cache.setQueryData(queryKeys.queue.all(), [queueEntry(4)]);

    applyLiveEvent(cache, 'queue-update', [queueEntry(4), queueEntry(5), queueEntry(3, 'SEATED')]);

    const rail = cache.getQueryData<ReturnType<typeof queueEntry>[]>(queryKeys.queue.all())!;
    expect(rail.map((entry) => entry.tokenNo)).toEqual([4, 5, 3]);
    expect(rail[2].status).toBe('SEATED');
  });

  it('empties the rail when the last token is seated', () => {
    const cache = client();
    cache.setQueryData(queryKeys.queue.all(), [queueEntry(4)]);

    applyLiveEvent(cache, 'queue-update', []);

    expect(cache.getQueryData(queryKeys.queue.all())).toEqual([]);
  });
});

describe('the other four', () => {
  it('booking-update writes the slot and unsettles both tabs', () => {
    const cache = client();
    cache.setQueryData(queryKeys.bookings.tab('upcoming'), [{ id: 12 }]);
    cache.setQueryData(queryKeys.bookings.tab('history'), []);

    applyLiveEvent(cache, 'booking-update', { id: 12, status: 'CANCELLED' });

    expect(cache.getQueryData(queryKeys.bookings.detail(12))).toEqual({
      id: 12,
      status: 'CANCELLED',
    });
    expect(cache.getQueryState(queryKeys.bookings.tab('upcoming'))?.isInvalidated).toBe(true);
    expect(cache.getQueryState(queryKeys.bookings.tab('history'))?.isInvalidated).toBe(true);
  });

  it('tournament-update writes the detail and unsettles the list and finance', () => {
    const cache = client();
    cache.setQueryData(queryKeys.tournaments.all(), [{ id: 7, status: 'OPEN' }]);
    cache.setQueryData(queryKeys.tournaments.finance(7), { pot: 1000 });

    const detail = { tournament: { id: 7, status: 'LIVE' }, entries: [], bracket: [] };
    applyLiveEvent(cache, 'tournament-update', detail);

    expect(cache.getQueryData(queryKeys.tournaments.detail(7))).toEqual(detail);
    expect(cache.getQueryState(queryKeys.tournaments.all())?.isInvalidated).toBe(true);
    expect(cache.getQueryState(queryKeys.tournaments.finance(7))?.isInvalidated).toBe(true);
  });

  it('alert lands newest first, and the same row twice stays once', () => {
    const cache = client();
    cache.setQueryData(queryKeys.alerts.all(), [{ id: 1, title: 'Low stock' }]);

    applyLiveEvent(cache, 'alert', { id: 2, title: 'Printer offline' });
    applyLiveEvent(cache, 'alert', { id: 2, title: 'Printer offline', read: true });

    expect(cache.getQueryData<{ id: number; read?: boolean }[]>(queryKeys.alerts.all())).toEqual([
      { id: 2, title: 'Printer offline', read: true },
      { id: 1, title: 'Low stock' },
    ]);
  });

  it('printer-status and sync-status replace their keys wholesale', () => {
    const cache = client();

    applyLiveEvent(cache, 'printer-status', [{ id: 'usb-1', status: 'OUT_OF_PAPER' }]);
    applyLiveEvent(cache, 'sync-status', { state: 'OFFLINE', pendingOps: 12 });

    expect(cache.getQueryData(queryKeys.printers.all())).toEqual([
      { id: 'usb-1', status: 'OUT_OF_PAPER' },
    ]);
    expect(cache.getQueryData(queryKeys.sync.status())).toEqual({
      state: 'OFFLINE',
      pendingOps: 12,
    });
  });
});

/* ------------------------------------------------------ the live stream */

describe('the live stream', () => {
  it('sends the bearer token and asks for an event stream', async () => {
    const cache = client();
    const wire = fakeStream();
    const fetchImpl = vi.fn(async (_url: string, _init?: RequestInit) =>
      streamResponse(wire.body),
    );

    start({
      queryClient: cache,
      url: '/events',
      getToken: () => 'access-token',
      fetchImpl: fetchImpl as unknown as typeof fetch,
    });

    await vi.waitFor(() => expect(fetchImpl).toHaveBeenCalled());
    const headers = fetchImpl.mock.calls[0][1]?.headers as Record<string, string>;
    expect(headers.Authorization).toBe('Bearer access-token');
    expect(headers.Accept).toBe('text/event-stream');
  });

  it('writes station-update and queue-update off the wire into the cache', async () => {
    const cache = client();
    cache.setQueryData(queryKeys.stations.all(), [station(1, 'FREE'), station(2, 'FREE')]);
    cache.setQueryData(queryKeys.queue.all(), []);

    const wire = fakeStream();
    const stream = start({
      queryClient: cache,
      url: '/events',
      getToken: () => 'token',
      fetchImpl: (async () => streamResponse(wire.body)) as unknown as typeof fetch,
    });

    await vi.waitFor(() => expect(stream.state()).toBe('live'));

    wire.ping();
    wire.send('station-update', station(2, 'RUNNING', { session: { id: 41, blocks: 1 } }));
    wire.send('queue-update', [queueEntry(7), queueEntry(8)]);

    await vi.waitFor(() => {
      const rows = cache.getQueryData<ReturnType<typeof station>[]>(queryKeys.stations.all())!;
      expect(rows[1].floorState).toBe('RUNNING');
      expect(
        cache.getQueryData<ReturnType<typeof queueEntry>[]>(queryKeys.queue.all()),
      ).toHaveLength(2);
    });
  });

  it('polls the live keys every 10 s once the stream drops, and stops on reconnect', async () => {
    const cache = client();
    const fetches: ReturnType<typeof fakeStream>[] = [];
    const fetchImpl = vi.fn(async () => {
      const wire = fakeStream();
      fetches.push(wire);
      return streamResponse(wire.body);
    });

    // The Floor is on screen: an active observer is what a poll can refetch.
    const stationFetch = vi.fn(async () => [station(1, 'FREE')]);
    const observer = new QueryObserver(cache, {
      queryKey: queryKeys.stations.all(),
      queryFn: stationFetch,
    });
    const unsubscribe = observer.subscribe(() => {});
    await vi.waitFor(() => expect(stationFetch).toHaveBeenCalledTimes(1));

    const stream = start({
      queryClient: cache,
      url: '/events',
      getToken: () => 'token',
      fetchImpl: fetchImpl as unknown as typeof fetch,
      pollIntervalMs: 30,
      reconnectDelayMs: 120,
    });

    await vi.waitFor(() => expect(stream.state()).toBe('live'));
    expect(stationFetch).toHaveBeenCalledTimes(1); // a live stream polls nothing

    fetches[0].fail();

    // The fallback fires at once — what was missed is already stale on screen —
    // and then keeps going on its own cadence.
    await vi.waitFor(() => expect(stream.state()).toBe('offline'));
    await vi.waitFor(() => expect(stationFetch.mock.calls.length).toBeGreaterThanOrEqual(3), {
      timeout: 2000,
    });

    // The reconnect lands; the polling stops rather than doubling up on SSE.
    await vi.waitFor(() => expect(stream.state()).toBe('live'), { timeout: 2000 });
    const afterReconnect = stationFetch.mock.calls.length;
    await new Promise((resolve) => setTimeout(resolve, 120));
    expect(stationFetch.mock.calls.length).toBe(afterReconnect);

    unsubscribe();
  });

  it('reconnects a stream the server closed cleanly without ever going offline', async () => {
    const cache = client();
    const wires: ReturnType<typeof fakeStream>[] = [];
    const states: string[] = [];
    const fetchImpl = vi.fn(async () => {
      const wire = fakeStream();
      wires.push(wire);
      return streamResponse(wire.body);
    });

    const stream = start({
      queryClient: cache,
      url: '/events',
      getToken: () => 'token',
      fetchImpl: fetchImpl as unknown as typeof fetch,
      onStateChange: (state) => states.push(state),
    });

    await vi.waitFor(() => expect(stream.state()).toBe('live'));
    wires[0].close(); // SseHub times every stream out on purpose

    await vi.waitFor(() => expect(fetchImpl).toHaveBeenCalledTimes(2));
    await vi.waitFor(() => expect(stream.state()).toBe('live'));
    expect(states).not.toContain('offline');
  });

  it('lets lib/api.ts handle a 401 rather than growing its own refresh', async () => {
    const cache = client();
    const onUnauthorized = vi.fn(async () => {});
    const fetchImpl = vi.fn(
      async () => ({ ok: false, status: 401, body: null }) as unknown as Response,
    );

    const stream = start({
      queryClient: cache,
      url: '/events',
      getToken: () => 'stale-token',
      fetchImpl: fetchImpl as unknown as typeof fetch,
      pollIntervalMs: 1000,
      reconnectDelayMs: 1000,
      onUnauthorized,
    });

    await vi.waitFor(() => expect(onUnauthorized).toHaveBeenCalled());
    expect(stream.state()).toBe('offline');
  });

  it('stops the stream and the fallback on close', async () => {
    const cache = client();
    const fetchImpl = vi.fn(async () => {
      throw new Error('venue box unreachable');
    });

    const stream = start({
      queryClient: cache,
      url: '/events',
      getToken: () => 'token',
      fetchImpl: fetchImpl as unknown as typeof fetch,
      pollIntervalMs: 20,
      reconnectDelayMs: 20,
    });

    await vi.waitFor(() => expect(stream.state()).toBe('offline'));
    stream.close();
    expect(stream.state()).toBe('closed');

    const attempts = fetchImpl.mock.calls.length;
    await new Promise((resolve) => setTimeout(resolve, 80));
    expect(fetchImpl.mock.calls.length).toBe(attempts);
  });
});
