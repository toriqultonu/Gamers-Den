'use client';

/**
 * `GET /events` — the live channel every screen hangs off, and the 10 s
 * polling fallback that covers it when it drops (api-contract.md, "Live
 * updates & sync"; frontend/ARCHITECTURE.md §4.1, §5.2).
 *
 * Three things live here, in the order they matter:
 *
 *   1. {@link applyLiveEvent} — the seven event names written into the exact
 *      canonical query keys (§4.1). Every payload is the shape of the GET it
 *      mirrors, which is what makes writing it straight into the cache the
 *      polling fallback fills safe: the two can never disagree about a shape.
 *   2. {@link startLiveStream} — the connection itself, reconnecting on its own
 *      and turning the fallback on the moment it is not live.
 *   3. {@link useLiveEvents} — the one line the shell mounts.
 *
 * **Why not `EventSource`.** `/events` is authenticated by the bearer header
 * like every other route, and the browser's `EventSource` cannot set one; the
 * alternative — the access token in the query string — would print
 * credentials into every proxy log. So the stream is a plain `fetch` whose
 * body is parsed here.
 *
 * **The fallback is not a nicety.** A dropped stream is silent: nothing tells
 * the floor that the card it is looking at stopped being true. So the instant
 * the stream is not live, the live-fed keys are re-fetched every 10 s and the
 * screens carry on being right, just slower.
 */

import { useEffect, useState } from 'react';
import { useQueryClient, type QueryClient } from '@tanstack/react-query';
import { API_BASE_URL, api, getAccessToken, type Schemas } from './api';
import { queryKeys } from './query-keys';

/* -------------------------------------------------------------- the events */

/** The `event:` names of `GET /events`, spelled as the backend spells them. */
export const LIVE_EVENT_NAMES = [
  'station-update',
  'queue-update',
  'booking-update',
  'tournament-update',
  'alert',
  'printer-status',
  'sync-status',
] as const;

export type LiveEventName = (typeof LIVE_EVENT_NAMES)[number];

/**
 * What each event carries — the shape of the GET it mirrors. `station-update`
 * and `booking-update` carry one row; `queue-update` and `printer-status`
 * carry the whole list, because that is what their GETs answer with.
 */
export type LiveEventPayloads = {
  'station-update': Schemas['Station'];
  'queue-update': Schemas['QueueEntry'][];
  'booking-update': Schemas['Booking'];
  'tournament-update': Schemas['TournamentDetail'];
  alert: Schemas['Alert'];
  'printer-status': Schemas['Printer'][];
  'sync-status': Schemas['SyncStatus'];
};

export function isLiveEventName(name: string): name is LiveEventName {
  return (LIVE_EVENT_NAMES as readonly string[]).includes(name);
}

/**
 * The keys the live channel feeds — and therefore exactly the keys the
 * fallback has to poll while it is down.
 *
 * `['sessions']` and `['bookings']` are prefixes on purpose: a `station-update`
 * moves whichever session detail and bill are open, and a `booking-update`
 * belongs to a tab the client cannot work out for itself. `['booking-settings']`
 * and `['terminal-settings']` are absent — they are not live facts, they change
 * when an admin changes them.
 */
export const LIVE_QUERY_KEYS: readonly unknown[][] = [
  [...queryKeys.stations.all()],
  [...queryKeys.sessions.all()],
  [...queryKeys.queue.all()],
  ['bookings'],
  [...queryKeys.tournaments.all()],
  [...queryKeys.alerts.all()],
  [...queryKeys.printers.all()],
  [...queryKeys.sync.status()],
];

/* ------------------------------------------------------- writing the cache */

/** Applies one decoded event to the cache. Unknown or malformed payloads are ignored. */
export function applyLiveEvent(client: QueryClient, name: LiveEventName, payload: unknown): void {
  switch (name) {
    case 'station-update':
      applyStationUpdate(client, payload);
      return;
    case 'queue-update':
      if (Array.isArray(payload)) {
        client.setQueryData(queryKeys.queue.all(), payload as Schemas['QueueEntry'][]);
      }
      return;
    case 'booking-update':
      applyBookingUpdate(client, payload);
      return;
    case 'tournament-update':
      applyTournamentUpdate(client, payload);
      return;
    case 'alert':
      applyAlert(client, payload);
      return;
    case 'printer-status':
      if (Array.isArray(payload)) {
        client.setQueryData(queryKeys.printers.all(), payload as Schemas['Printer'][]);
      }
      return;
    case 'sync-status':
      if (isObject(payload)) {
        client.setQueryData(queryKeys.sync.status(), payload as Schemas['SyncStatus']);
      }
      return;
  }
}

/**
 * One Floor card, replaced in place in `['stations']`.
 *
 * The list is only patched when there already is one: seeding it from a single
 * event would render a floor of one console. With nothing cached there is also
 * nothing on screen, and the first fetch brings the truth.
 *
 * The card carries a session *summary*; the panel reading `['sessions', id]`
 * wants the full session and its bill, so those are marked stale rather than
 * guessed at from the summary.
 */
function applyStationUpdate(client: QueryClient, payload: unknown): void {
  if (!isObject(payload)) return;
  const station = payload as Schemas['Station'];
  if (typeof station.id !== 'number') return;

  client.setQueryData<Schemas['Station'][]>(queryKeys.stations.all(), (current) => {
    if (!current) return current;
    const index = current.findIndex((row) => row.id === station.id);
    if (index === -1) return [...current, station];
    const next = current.slice();
    next[index] = station;
    return next;
  });

  const sessionId = station.session?.id;
  if (typeof sessionId === 'number') {
    // Prefix match: the detail and its bill (`['sessions', id, 'bill']`) both.
    void client.invalidateQueries({ queryKey: queryKeys.sessions.detail(sessionId) });
  }
}

/**
 * One slot into `['bookings', id]`; both tabs are marked stale because whether
 * a booking belongs to Upcoming or History is the server's call, and a
 * cancellation moves it between them.
 */
function applyBookingUpdate(client: QueryClient, payload: unknown): void {
  if (!isObject(payload)) return;
  const booking = payload as Schemas['Booking'];
  if (typeof booking.id !== 'number') return;
  client.setQueryData(queryKeys.bookings.detail(booking.id), booking);
  void client.invalidateQueries({ queryKey: queryKeys.bookings.tab('upcoming') });
  void client.invalidateQueries({ queryKey: queryKeys.bookings.tab('history') });
}

/**
 * The event with its entries, consoles and bracket. The finance rail is
 * Manager+ and is never pushed, so it is invalidated rather than written — a
 * cashier's cache simply has nothing there to refetch.
 */
function applyTournamentUpdate(client: QueryClient, payload: unknown): void {
  if (!isObject(payload)) return;
  const detail = payload as Schemas['TournamentDetail'];
  const id = detail.tournament?.id;
  if (typeof id !== 'number') return;
  client.setQueryData(queryKeys.tournaments.detail(id), detail);
  void client.invalidateQueries({ queryKey: queryKeys.tournaments.all() });
  void client.invalidateQueries({ queryKey: queryKeys.tournaments.finance(id) });
}

/** Newest first, de-duplicated by id — the same row can arrive twice across a reconnect. */
function applyAlert(client: QueryClient, payload: unknown): void {
  if (!isObject(payload)) return;
  const alert = payload as Schemas['Alert'];
  if (typeof alert.id !== 'number') return;
  client.setQueryData<Schemas['Alert'][]>(queryKeys.alerts.all(), (current) => {
    if (!current) return current;
    return [alert, ...current.filter((row) => row.id !== alert.id)];
  });
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/* ------------------------------------------------------------ wire parsing */

/** One dispatched `event:`/`data:` block. */
export type LiveFrame = {
  event: string;
  data: string;
  id?: string;
  /** The server's `retry:` hint, in milliseconds. */
  retry?: number;
};

/**
 * The `text/event-stream` grammar, fed a chunk at a time.
 *
 * Incremental because a frame is routinely split across two reads. Lines end
 * with LF, CRLF or a lone CR; a CR at the very end of the buffer is held back,
 * since the LF that would make it a CRLF may still be coming.
 */
export class SseParser {
  private buffer = '';
  private event = '';
  private data: string[] = [];
  private lastId: string | undefined;
  private retry: number | undefined;

  push(chunk: string): LiveFrame[] {
    this.buffer += chunk;
    const frames: LiveFrame[] = [];

    for (;;) {
      const lf = this.buffer.indexOf('\n');
      const cr = this.buffer.indexOf('\r');
      if (lf === -1 && cr === -1) break;

      let end: number;
      let width: number;
      if (cr !== -1 && (lf === -1 || cr < lf)) {
        // A trailing CR might still become CRLF — wait for the next chunk.
        if (cr === this.buffer.length - 1) break;
        end = cr;
        width = this.buffer[cr + 1] === '\n' ? 2 : 1;
      } else {
        end = lf;
        width = 1;
      }

      const line = this.buffer.slice(0, end);
      this.buffer = this.buffer.slice(end + width);
      const frame = this.line(line);
      if (frame) frames.push(frame);
    }

    return frames;
  }

  /** Dispatches whatever a closed stream left behind, if it was complete. */
  end(): LiveFrame[] {
    const rest = this.buffer;
    this.buffer = '';
    const frames: LiveFrame[] = [];
    if (rest !== '') {
      const frame = this.line(rest);
      if (frame) frames.push(frame);
    }
    return frames;
  }

  private line(line: string): LiveFrame | null {
    // Blank line: dispatch. A comment (`: ping`, the heartbeat) is ignored.
    if (line === '') return this.flush();
    if (line.startsWith(':')) return null;

    const colon = line.indexOf(':');
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) value = value.slice(1);

    switch (field) {
      case 'event':
        this.event = value;
        break;
      case 'data':
        this.data.push(value);
        break;
      case 'id':
        this.lastId = value;
        break;
      case 'retry': {
        const ms = Number(value);
        if (Number.isFinite(ms)) this.retry = ms;
        break;
      }
      default:
        break;
    }
    return null;
  }

  /** An empty data buffer dispatches nothing — that is the spec, and the heartbeat. */
  private flush(): LiveFrame | null {
    if (this.data.length === 0) {
      this.event = '';
      return null;
    }
    const frame: LiveFrame = {
      event: this.event || 'message',
      data: this.data.join('\n'),
      id: this.lastId,
      retry: this.retry,
    };
    this.event = '';
    this.data = [];
    return frame;
  }
}

/** Decodes one frame and writes it into the cache. `false` = not ours, or not JSON. */
export function applyLiveFrame(client: QueryClient, frame: LiveFrame): boolean {
  if (!isLiveEventName(frame.event)) return false;
  let payload: unknown;
  try {
    payload = JSON.parse(frame.data);
  } catch {
    return false;
  }
  applyLiveEvent(client, frame.event, payload);
  return true;
}

/** Drains a response body into frames. Resolves when the server closes the stream. */
export async function readEventStream(
  body: ReadableStream<Uint8Array>,
  onFrame: (frame: LiveFrame) => void,
): Promise<void> {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  const parser = new SseParser();
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      if (value === undefined) continue;
      const text = typeof value === 'string' ? value : decoder.decode(value, { stream: true });
      for (const frame of parser.push(text)) onFrame(frame);
    }
    for (const frame of parser.end()) onFrame(frame);
  } finally {
    reader.releaseLock();
  }
}

/* -------------------------------------------------------------- the stream */

export type LiveConnectionState =
  /** Not subscribed — signed out, or the shell has unmounted it. */
  | 'idle'
  /** The first connect, or a reconnect that has not answered yet. */
  | 'connecting'
  /** Subscribed; the cache is being pushed to. */
  | 'live'
  /** The stream is down and the 10 s fallback is carrying the screens. */
  | 'offline'
  | 'closed';

export type LiveStreamOptions = {
  queryClient: QueryClient;
  url?: string;
  getToken?: () => string | null;
  fetchImpl?: typeof fetch;
  /** api-contract.md: "Polling fallback 10 s". */
  pollIntervalMs?: number;
  /** Matches the server's own `retry:` hint, so a reconnect beats the next poll. */
  reconnectDelayMs?: number;
  onStateChange?: (state: LiveConnectionState) => void;
  /**
   * What to do about a 401 on the stream. The default re-reads the sync status
   * through `lib/api.ts`, which performs its one silent refresh (and the hard
   * logout if that fails) — the stream then reconnects with the new token
   * rather than growing a second copy of the auth dance.
   */
  onUnauthorized?: () => Promise<void>;
};

export type LiveStream = {
  /** Ends the subscription and the fallback. Idempotent. */
  close(): void;
  state(): LiveConnectionState;
};

const DEFAULT_POLL_MS = 10_000;
const DEFAULT_RECONNECT_MS = 3_000;

/**
 * Subscribes to `/events` and keeps the subscription alive.
 *
 * The loop is deliberately plain: connect, read until the stream ends, connect
 * again. A *clean* end is the server's periodic close (`SseHub` times every
 * stream out on purpose) and is reconnected to immediately, without telling
 * the screens anything happened. A *failed* connect or a broken read is a real
 * outage: the fallback starts at once and the reconnect waits.
 */
export function startLiveStream(options: LiveStreamOptions): LiveStream {
  const {
    queryClient,
    url = `${API_BASE_URL}/events`,
    getToken = getAccessToken,
    fetchImpl,
    pollIntervalMs = DEFAULT_POLL_MS,
    reconnectDelayMs = DEFAULT_RECONNECT_MS,
    onStateChange,
    onUnauthorized = refreshThroughApi,
  } = options;

  const doFetch: typeof fetch = fetchImpl ?? ((input, init) => fetch(input, init));

  let state: LiveConnectionState = 'connecting';
  let closed = false;
  let controller: AbortController | null = null;
  let pollTimer: ReturnType<typeof setInterval> | null = null;
  let wake: (() => void) | null = null;

  const setState = (next: LiveConnectionState) => {
    if (state === next || state === 'closed') return;
    state = next;
    onStateChange?.(next);
  };

  const pollOnce = () => {
    for (const key of LIVE_QUERY_KEYS) {
      void queryClient.invalidateQueries({ queryKey: key, refetchType: 'active' });
    }
  };

  const startPolling = () => {
    if (pollTimer) return;
    // Immediately, because whatever was missed while the stream was down is
    // already stale on screen — then on the contract's 10 s cadence.
    pollOnce();
    pollTimer = setInterval(pollOnce, pollIntervalMs);
  };

  const stopPolling = () => {
    if (!pollTimer) return;
    clearInterval(pollTimer);
    pollTimer = null;
  };

  /** Resolves when the stream ends cleanly; throws when it never opened or broke. */
  const connectOnce = async (): Promise<void> => {
    controller = new AbortController();
    const token = getToken();
    const response = await doFetch(url, {
      method: 'GET',
      headers: {
        Accept: 'text/event-stream',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      credentials: 'include',
      cache: 'no-store',
      signal: controller.signal,
    });

    if (response.status === 401) {
      await onUnauthorized();
      throw new Error('events: unauthorized');
    }
    if (!response.ok || !response.body) {
      throw new Error(`events: ${response.status}`);
    }

    setState('live');
    stopPolling();
    await readEventStream(response.body, (frame) => applyLiveFrame(queryClient, frame));
  };

  const sleep = (ms: number) =>
    new Promise<void>((resolve) => {
      const finish = () => {
        clearTimeout(timer);
        wake = null;
        resolve();
      };
      const timer = setTimeout(finish, ms);
      wake = finish;
    });

  const run = async () => {
    while (!closed) {
      if (state !== 'offline') setState('connecting');
      let clean = true;
      try {
        await connectOnce();
      } catch {
        clean = false;
      }
      if (closed) break;
      // A clean end is the server rotating the stream: reconnect straight away
      // and leave the screens none the wiser.
      if (clean) continue;
      setState('offline');
      startPolling();
      await sleep(reconnectDelayMs);
    }
    stopPolling();
    setState('closed');
  };

  // The browser noticing the network came back is worth more than the timer.
  const onOnline = () => wake?.();
  if (typeof window !== 'undefined') window.addEventListener('online', onOnline);

  void run();

  return {
    close() {
      if (closed) return;
      closed = true;
      if (typeof window !== 'undefined') window.removeEventListener('online', onOnline);
      controller?.abort();
      wake?.();
      stopPolling();
      setState('closed');
    },
    state: () => state,
  };
}

async function refreshThroughApi(): Promise<void> {
  try {
    await api.get('/sync/status');
  } catch {
    // `lib/api.ts` has already refreshed or signed the terminal out; either
    // way, the next connect attempt is where that shows up.
  }
}

/* ---------------------------------------------------------------- the hook */

/**
 * Mounts the live channel for as long as somebody is signed in, and reports
 * what it is doing. The shell is the only caller — one stream per terminal,
 * not one per screen.
 */
export function useLiveEvents({ enabled = true }: { enabled?: boolean } = {}): LiveConnectionState {
  const queryClient = useQueryClient();
  const [state, setState] = useState<LiveConnectionState>('idle');

  useEffect(() => {
    if (!enabled) {
      setState('idle');
      return;
    }
    const stream = startLiveStream({ queryClient, onStateChange: setState });
    return () => stream.close();
  }, [enabled, queryClient]);

  return state;
}
