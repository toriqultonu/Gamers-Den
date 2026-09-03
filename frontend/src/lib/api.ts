/**
 * The one way this app talks to the backend.
 *
 * Four jobs, all of them fixed by docs/api-contract.md §1 and
 * frontend/ARCHITECTURE.md §4.4 / §5.4:
 *
 *   1. the bearer token on every call (the refresh cookie rides along by itself);
 *   2. an `Idempotency-Key` on every money/print mutation — **one key per user
 *      intent, reused on retry**, so a retried settle cannot double-charge;
 *   3. the error envelope parsed **once** into a typed {@link ApiError} whose
 *      `code` the screens switch on (`CANCEL_CUTOFF_PASSED` renders the cutoff
 *      lock note, `PREBOOKING_DISABLED` the feature notice, and so on);
 *   4. 401 → exactly one silent refresh → hard logout.
 *
 * An error never destroys entered data: this module throws, it never navigates
 * and never clears a form. Logout is the single exception, and it is routed
 * through the handler F04 installs rather than done here.
 *
 * Response shapes come from `api-types.ts`, generated from the backend's
 * OpenAPI document — see `scripts/generate-api-types.mjs`.
 */

import type { components } from './api-types';
import { noteServerTime, resetServerTime } from './time';

/** Generated response/request shapes, re-exported under a shorter name. */
export type Schemas = components['schemas'];

/**
 * Where the venue backend lives. Same-origin in the venue image (a reverse
 * proxy fronts both); the env var covers a developer running Next and Spring
 * on separate ports.
 */
export const API_BASE_URL =
  process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8080/api/v1';

/* ------------------------------------------------------------------ errors */

/** api-contract.md §1 — the codes every non-2xx can carry, whatever the route. */
export const STANDARD_ERROR_CODES = [
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
] as const;

/**
 * backend/ARCHITECTURE.md §4.4 — the canonical spellings of the domain 409s.
 * The frontend switches on these; a typo here is a silently broken screen.
 */
export const DOMAIN_ERROR_CODES = [
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
] as const;

/**
 * Conditions the client itself raises. They are not server codes — they exist
 * so a screen can treat "the venue box is unreachable" like any other error
 * instead of catching a bare `TypeError` from `fetch`.
 */
export const CLIENT_ERROR_CODES = ['NETWORK_ERROR', 'UNKNOWN'] as const;

export const ERROR_CODES = [
  ...STANDARD_ERROR_CODES,
  ...DOMAIN_ERROR_CODES,
  ...CLIENT_ERROR_CODES,
] as const;

export type StandardErrorCode = (typeof STANDARD_ERROR_CODES)[number];
export type DomainErrorCode = (typeof DOMAIN_ERROR_CODES)[number];
export type ClientErrorCode = (typeof CLIENT_ERROR_CODES)[number];
export type KnownErrorCode = (typeof ERROR_CODES)[number];

/**
 * A known code, or whatever a newer backend sent. Unknown codes stay verbatim
 * rather than collapsing into `UNKNOWN` — the traceId and message still have to
 * be readable in a bug report — while `KnownErrorCode` keeps autocomplete on
 * the `switch` statements that matter.
 */
export type ApiErrorCode = KnownErrorCode | (string & {});

/** The envelope every non-2xx response carries (api-contract.md §1). */
export type ErrorEnvelope = {
  error: {
    code: string;
    message: string;
    details?: Record<string, unknown>;
    traceId?: string;
  };
};

/** Statuses that describe themselves when the body is missing or unreadable. */
const STATUS_FALLBACK_CODES: Record<number, StandardErrorCode> = {
  400: 'VALIDATION_FAILED',
  401: 'UNAUTHORIZED',
  403: 'FORBIDDEN',
  404: 'NOT_FOUND',
  409: 'CONFLICT',
  423: 'LOCKED_PIN',
  429: 'RATE_LIMITED',
};

/**
 * The envelope, parsed once. Screens read `code`; `details` carries the
 * server's field errors and `traceId` is what a support call quotes.
 */
export class ApiError extends Error {
  readonly name = 'ApiError';
  readonly status: number;
  readonly code: ApiErrorCode;
  readonly details?: Record<string, unknown>;
  readonly traceId?: string;

  constructor(init: {
    status: number;
    code: ApiErrorCode;
    message: string;
    details?: Record<string, unknown>;
    traceId?: string;
  }) {
    super(init.message);
    this.status = init.status;
    this.code = init.code;
    this.details = init.details;
    this.traceId = init.traceId;
  }

  /** `error.is('CANCEL_CUTOFF_PASSED')` — the shape the screens read best. */
  is(...codes: ApiErrorCode[]): boolean {
    return codes.includes(this.code);
  }
}

export function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError;
}

/** True for the code, whatever the thrown value turns out to be. */
export function hasErrorCode(error: unknown, ...codes: ApiErrorCode[]): boolean {
  return isApiError(error) && error.is(...codes);
}

/**
 * The house copy for the codes design.md §1 gives a state to. Anything absent
 * falls back to the server's own message — which is already human-readable —
 * so a new backend code degrades to plain prose, never to a blank banner.
 */
export const ERROR_NOTICES: Partial<Record<KnownErrorCode, string>> = {
  // Floor
  STATION_BUSY: 'That console already has a live session.',
  STATION_RESERVED: 'This console is reserved for a tournament match.',
  STATION_IN_USE: 'This console is in use — end the session before removing it.',
  BLOCKS_CONSUMED: 'That time has already been played or paid for.',
  NO_BLOCKS: 'Add play time before starting the clock.',
  SESSION_HAS_BALANCE: 'Settle the outstanding balance before ending this session.',
  // POS / money
  OUT_OF_STOCK: 'Not enough stock left for that item.',
  SPLIT_MISMATCH: 'The split does not add up to the amount due — the bill is unchanged.',
  WALLET_INSUFFICIENT: 'Not enough wallet balance for that amount.',
  INSUFFICIENT_POINTS: 'Not enough points to redeem that much.',
  PAYMENT_REF_REQUIRED: 'Enter the bKash/Nagad TrxID to record this payment.',
  // Bookings & queue
  PREBOOKING_DISABLED: 'Pre-booking is switched off in Setup.',
  CANCEL_CUTOFF_PASSED:
    'Too close to the start time to cancel — refunds close before the cutoff.',
  ALREADY_CHECKED_IN: 'This booking has already been checked in.',
  CONSOLE_TYPE_MISMATCH: 'This token was sold for a different console type.',
  NO_FREE_CONSOLE: 'No free console of that type right now.',
  // Tournaments
  TOURNAMENT_FULL: 'This tournament is full.',
  TOURNAMENT_NOT_OPEN: 'Entries are closed for this tournament.',
  NOT_ENOUGH_PLAYERS: 'Not enough entries to draw a bracket yet.',
  // Staff & shifts
  DUPLICATE_NAME: 'That name is already taken.',
  DUPLICATE_PHONE: 'A member with that phone number already exists.',
  STAFF_ON_SHIFT: 'That staff member is on shift — close the shift first.',
  SHIFT_ALREADY_OPEN: 'A shift is already open on this terminal.',
  // Infrastructure
  LOCKED_PIN: 'Too many wrong PINs — this staff account is locked for 15 minutes.',
  FORBIDDEN: 'Your role does not have access to this.',
  RATE_LIMITED: 'Too many attempts — wait a moment and try again.',
  PRINTER_UNAVAILABLE: 'The printer is not responding — the job is queued for retry.',
  SYNC_UNAVAILABLE: 'The cloud is unreachable. Everything still works locally.',
  IDEMPOTENCY_REPLAY: 'That request was already sent with different details.',
  NETWORK_ERROR: 'Cannot reach the venue server.',
};

/** The banner/notice text for a caught error, whatever it is. */
export function errorNotice(error: unknown, fallback = 'Something went wrong.'): string {
  if (!isApiError(error)) return fallback;
  return ERROR_NOTICES[error.code as KnownErrorCode] ?? error.message ?? fallback;
}

/* ------------------------------------------------------------------- auth */

let accessToken: string | null = null;
let onLogout: (() => void) | null = null;

/** Held in memory only — a POS terminal is shared, and XSS must not lift it. */
export function setAccessToken(token: string | null): void {
  accessToken = token;
}

export function getAccessToken(): string | null {
  return accessToken;
}

/**
 * Installs the "we are signed out" handler (F04 redirects to S1). Called once
 * from the app shell; the api layer never navigates on its own.
 */
export function configureApi(options: { onLogout?: () => void }): void {
  onLogout = options.onLogout ?? null;
}

/** Clears local auth state and tells the shell. Does not call the server. */
export function forgetSession(): void {
  accessToken = null;
  resetIdempotencyKeys();
  resetServerTime();
  onLogout?.();
}

/** The explicit sign-out button: revoke the refresh family, then forget. */
export async function logout(): Promise<void> {
  try {
    await fetch(`${API_BASE_URL}/auth/logout`, {
      method: 'POST',
      credentials: 'include',
    });
  } catch {
    // A sign-out must succeed locally even with the backend down.
  }
  forgetSession();
}

/* ----------------------------------------------------------- idempotency */

/**
 * The guarded routes, mirroring the backend's `IdempotencyPolicy` line for
 * line (api-contract.md §1). Sending one of these without a key earns a 400,
 * so the client refuses to send it at all.
 */
const IDEMPOTENT_ROUTES: readonly { method: string; pattern: RegExp }[] = [
  { method: 'POST', pattern: /^\/payments$/ },
  { method: 'POST', pattern: /^\/print-jobs$/ },
  { method: 'POST', pattern: /^\/sessions\/[^/]+\/blocks$/ },
  { method: 'POST', pattern: /^\/members\/[^/]+\/wallet\/[^/]+$/ },
  { method: 'POST', pattern: /^\/tournaments\/[^/]+\/entries$/ },
  { method: 'POST', pattern: /^\/bookings$/ },
  { method: 'POST', pattern: /^\/bookings\/[^/]+\/cancel$/ },
  { method: 'POST', pattern: /^\/play-tickets$/ },
];

/** Whether this call must carry an `Idempotency-Key`. */
export function requiresIdempotencyKey(method: string, path: string): boolean {
  const route = normalisePath(path);
  return IDEMPOTENT_ROUTES.some(
    (candidate) => candidate.method === method.toUpperCase() && candidate.pattern.test(route),
  );
}

/**
 * intent → key. An *intent* is one thing the operator meant to do ("settle
 * bill 41", "confirm this booking form"). The key lives as long as the intent
 * does: a retry after a timeout, a 503 or a lost network reuses it, so the
 * server replays its stored answer instead of charging twice. It is released
 * on success — and on the failures that require the operator to change the
 * payload, because a changed body under the same key is a 409.
 */
const keysByIntent = new Map<string, string>();

/** The key for an intent, minted on first use. */
export function idempotencyKeyFor(intent: string): string {
  const existing = keysByIntent.get(intent);
  if (existing) return existing;
  const key = randomUuid();
  keysByIntent.set(intent, key);
  return key;
}

/** The key an intent is currently holding, if any. */
export function currentIdempotencyKey(intent: string): string | undefined {
  return keysByIntent.get(intent);
}

/** Ends an intent — the next call under that name mints a fresh key. */
export function releaseIdempotencyKey(intent: string): void {
  keysByIntent.delete(intent);
}

/** Sign-out and test isolation. */
export function resetIdempotencyKeys(): void {
  keysByIntent.clear();
}

/**
 * After a failure, decide whether the key survives.
 *
 * Kept for anything a plain retry can fix (network, 5xx, 429, the in-flight
 * 409) — that is the whole point of the key. Dropped when the operator must
 * edit the payload first (validation) or when the server already saw this key
 * with a different body, since retrying either under the old key can only 409.
 */
function keySurvives(error: ApiError): boolean {
  if (error.code === 'IDEMPOTENCY_REPLAY') return false;
  if (error.status === 400 || error.status === 422) return false;
  return true;
}

function randomUuid(): string {
  const cryptoRef = globalThis.crypto;
  if (cryptoRef?.randomUUID) return cryptoRef.randomUUID();
  // Node without WebCrypto (older CI images) — the backend only requires a UUID.
  const bytes = new Uint8Array(16);
  for (let i = 0; i < bytes.length; i += 1) bytes[i] = Math.floor(Math.random() * 256);
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/* ---------------------------------------------------------------- request */

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

export type QueryValue = string | number | boolean | null | undefined;

export type ApiRequestOptions = {
  method?: HttpMethod;
  /** Serialized as JSON. `FormData` is passed through untouched (login-bg upload). */
  body?: unknown;
  query?: Record<string, QueryValue>;
  headers?: Record<string, string>;
  signal?: AbortSignal;
  /**
   * The user intent this call belongs to. Required on the guarded money/print
   * routes; ignored elsewhere.
   */
  intent?: string;
  /** Skips the bearer header and the 401-refresh dance (login, refresh). */
  anonymous?: boolean;
};

export type ApiResponse<T> = {
  data: T;
  status: number;
  /** The server replayed a stored response for this `Idempotency-Key`. */
  replayed: boolean;
};

/** Full response — status and the replay flag included. */
export async function apiRequest<T>(
  path: string,
  options: ApiRequestOptions = {},
): Promise<ApiResponse<T>> {
  const method = (options.method ?? 'GET').toUpperCase() as HttpMethod;
  const url = buildUrl(path, options.query);

  let idempotencyKey: string | undefined;
  if (requiresIdempotencyKey(method, path)) {
    if (!options.intent) {
      throw new Error(
        `${method} ${path} requires an Idempotency-Key: pass an \`intent\` naming the ` +
          `operator action, so a retry reuses the same key (api-contract.md §1).`,
      );
    }
    idempotencyKey = idempotencyKeyFor(options.intent);
  }

  const send = () =>
    dispatch(url, {
      method,
      body: options.body,
      headers: options.headers,
      signal: options.signal,
      idempotencyKey,
      anonymous: options.anonymous,
    });

  let response = await send();

  // 401 → one silent refresh → hard logout (§4.4). Never more than one refresh
  // per call, and never for the auth routes themselves.
  if (response.status === 401 && !options.anonymous && !isAuthRoute(path)) {
    const refreshed = await refreshAccessToken();
    if (!refreshed) {
      forgetSession();
      throw await toApiError(response);
    }
    response = await send();
    if (response.status === 401) {
      forgetSession();
      throw await toApiError(response);
    }
  }

  if (!response.ok) {
    const error = await toApiError(response);
    if (options.intent && !keySurvives(error)) releaseIdempotencyKey(options.intent);
    throw error;
  }

  // The intent is done: the next one under that name gets a fresh key.
  if (options.intent) releaseIdempotencyKey(options.intent);

  return {
    data: (await readBody(response)) as T,
    status: response.status,
    replayed: response.headers.get('Idempotency-Replayed') === 'true',
  };
}

/** The everyday call: the parsed body, or a thrown {@link ApiError}. */
export async function apiFetch<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
  const { data } = await apiRequest<T>(path, options);
  return data;
}

export const api = {
  get: <T>(path: string, options: Omit<ApiRequestOptions, 'method' | 'body'> = {}) =>
    apiFetch<T>(path, { ...options, method: 'GET' }),
  post: <T>(path: string, body?: unknown, options: Omit<ApiRequestOptions, 'method' | 'body'> = {}) =>
    apiFetch<T>(path, { ...options, method: 'POST', body }),
  put: <T>(path: string, body?: unknown, options: Omit<ApiRequestOptions, 'method' | 'body'> = {}) =>
    apiFetch<T>(path, { ...options, method: 'PUT', body }),
  patch: <T>(path: string, body?: unknown, options: Omit<ApiRequestOptions, 'method' | 'body'> = {}) =>
    apiFetch<T>(path, { ...options, method: 'PATCH', body }),
  delete: <T>(path: string, options: Omit<ApiRequestOptions, 'method' | 'body'> = {}) =>
    apiFetch<T>(path, { ...options, method: 'DELETE' }),
} as const;

/* --------------------------------------------------------------- internals */

type DispatchOptions = {
  method: HttpMethod;
  body?: unknown;
  headers?: Record<string, string>;
  signal?: AbortSignal;
  idempotencyKey?: string;
  anonymous?: boolean;
};

async function dispatch(url: string, options: DispatchOptions): Promise<Response> {
  const headers = new Headers(options.headers);
  headers.set('Accept', 'application/json');

  const isForm = typeof FormData !== 'undefined' && options.body instanceof FormData;
  let body: BodyInit | undefined;
  if (options.body !== undefined && options.method !== 'GET') {
    if (isForm) {
      body = options.body as FormData;
    } else {
      headers.set('Content-Type', 'application/json');
      body = JSON.stringify(options.body);
    }
  }

  if (!options.anonymous && accessToken) headers.set('Authorization', `Bearer ${accessToken}`);
  if (options.idempotencyKey) headers.set('Idempotency-Key', options.idempotencyKey);

  let response: Response;
  try {
    response = await fetch(url, {
      method: options.method,
      headers,
      body,
      signal: options.signal,
      // The refresh cookie is HttpOnly and scoped to /api/v1/auth; it only ever
      // rides along because of this.
      credentials: 'include',
    });
  } catch (cause) {
    throw new ApiError({
      status: 0,
      code: 'NETWORK_ERROR',
      message: 'Cannot reach the venue server.',
      details: { cause: String(cause) },
    });
  }

  // Every response re-measures the offset the countdowns tick from (§5.2).
  noteServerTime(response.headers.get('Date'));
  return response;
}

/** One refresh at a time, however many calls hit 401 together. */
let refreshInFlight: Promise<Schemas['SessionResponse'] | null> | null = null;

/**
 * Rotate the refresh cookie once, and hand every concurrent caller the same
 * rotation.
 *
 * **The refresh family is single-use.** The backend revokes the whole family
 * when a token is presented twice (`RefreshTokenService`: "refresh token reuse
 * detected — family revoked"), which is the right behaviour against a stolen
 * cookie and a trap for us: on a page load the screen's queries fire before the
 * session has been restored, 401, and ask for a refresh — while the restore is
 * already rotating the same cookie. Two rotations of one token is a reuse, and
 * the terminal is signed out mid-shift for no reason.
 *
 * So there is exactly one refresh in this module and both callers share it: the
 * 401 path below, and `features/auth/session.tsx` restoring a reloaded
 * terminal. It answers with the session rather than a boolean because the
 * restore needs the staff behind it.
 */
export function refreshSession(): Promise<Schemas['SessionResponse'] | null> {
  refreshInFlight ??= performRefresh().finally(() => {
    refreshInFlight = null;
  });
  return refreshInFlight;
}

async function refreshAccessToken(): Promise<boolean> {
  return (await refreshSession()) !== null;
}

async function performRefresh(): Promise<Schemas['SessionResponse'] | null> {
  try {
    const response = await fetch(`${API_BASE_URL}/auth/refresh`, {
      method: 'POST',
      headers: { Accept: 'application/json' },
      credentials: 'include',
    });
    noteServerTime(response.headers.get('Date'));
    if (!response.ok) return null;
    const session = (await response.json()) as Schemas['SessionResponse'];
    if (!session?.accessToken) return null;
    setAccessToken(session.accessToken);
    return session;
  } catch {
    return null;
  }
}

async function toApiError(response: Response): Promise<ApiError> {
  const fallbackCode = STATUS_FALLBACK_CODES[response.status] ?? 'UNKNOWN';
  let envelope: Partial<ErrorEnvelope> | null = null;
  try {
    envelope = (await response.clone().json()) as Partial<ErrorEnvelope>;
  } catch {
    envelope = null;
  }
  const error = envelope?.error;
  return new ApiError({
    status: response.status,
    code: error?.code ?? fallbackCode,
    message: error?.message ?? `${response.status} ${response.statusText || 'Request failed'}`,
    details: error?.details,
    traceId: error?.traceId,
  });
}

async function readBody(response: Response): Promise<unknown> {
  if (response.status === 204) return undefined;
  const type = response.headers.get('Content-Type') ?? '';
  const text = await response.text();
  if (text === '') return undefined;
  if (type.includes('json') || type === '' || type.includes('*/*')) {
    try {
      return JSON.parse(text);
    } catch {
      return text;
    }
  }
  return text;
}

function buildUrl(path: string, query?: Record<string, QueryValue>): string {
  const base = `${API_BASE_URL}${path.startsWith('/') ? path : `/${path}`}`;
  if (!query) return base;
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue;
    params.set(key, String(value));
  }
  const search = params.toString();
  return search ? `${base}?${search}` : base;
}

/** Path without its query string and trailing slash — what the routes match. */
function normalisePath(path: string): string {
  const withoutQuery = path.split('?')[0] ?? '';
  return withoutQuery.length > 1 && withoutQuery.endsWith('/')
    ? withoutQuery.slice(0, -1)
    : withoutQuery;
}

function isAuthRoute(path: string): boolean {
  return normalisePath(path).startsWith('/auth/');
}
