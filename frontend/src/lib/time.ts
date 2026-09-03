/**
 * Server-offset clock — the only clock this app is allowed to believe.
 *
 * frontend/ARCHITECTURE.md §5.2: *all* countdowns (session blocks AND
 * tournament matches) tick from a server `remainingSeconds` plus the offset
 * measured here; a terminal whose Windows clock is ten minutes fast must still
 * show the same time left as the till beside it. `lib/api.ts` feeds
 * {@link noteServerTime} from the `Date` header of every response, so the
 * offset re-measures itself on ordinary traffic — no clock endpoint to poll.
 *
 * Day rollover (§5.12) is the venue's, not the browser's: queue tokens reset at
 * `Asia/Dhaka` midnight, so "is this token from today?" is answered in venue
 * time even on a terminal left on some other timezone.
 */

/** Venue timezone — token rollover, business days and displayed wall-clock. */
export const VENUE_TIMEZONE = 'Asia/Dhaka';

/** One block of play time, in seconds (30 minutes — docs/bookings.md). */
export const BLOCK_SECONDS = 30 * 60;

/** serverNow() - Date.now(), in milliseconds. Zero until the first response. */
let offsetMs = 0;
let measured = false;

/**
 * Record the server's own timestamp for a response we just received.
 *
 * `receivedAt` defaults to now; pass the local time the response landed if the
 * value is being replayed later. Round-trip latency is not corrected for — a
 * LAN POS is sub-millisecond and a wrong-by-minutes wall clock is the failure
 * this guards against, not a wrong-by-milliseconds one.
 */
export function noteServerTime(
  serverTime: string | number | Date | null | undefined,
  receivedAt: number = Date.now(),
): void {
  const serverMs = toMillis(serverTime);
  if (serverMs === null) return;
  offsetMs = serverMs - receivedAt;
  measured = true;
}

/** Milliseconds the server is ahead of this machine (negative = behind). */
export function serverOffsetMs(): number {
  return offsetMs;
}

/** True once a real server timestamp has been seen. */
export function hasServerTime(): boolean {
  return measured;
}

/** Now, in server time. Every countdown and cutoff comparison starts here. */
export function serverNow(): number {
  return Date.now() + offsetMs;
}

/** Drops the measured offset — sign-out, and test isolation. */
export function resetServerTime(): void {
  offsetMs = 0;
  measured = false;
}

/**
 * A clock reading as the server handed it over: how much play time was left,
 * when that was true, and whether it is draining.
 *
 * `Session` sends exactly this (`remainingSeconds`, `serverTime`, `state`), and
 * so does a tournament match — hence one shared shape.
 */
export type ClockSnapshot = {
  remainingSeconds: number;
  /** The server timestamp the reading belongs to (`serverTime` / `asOf`). */
  asOf: string | number | Date;
  /** A paused or not-yet-started clock holds its reading. */
  running: boolean;
};

/**
 * Seconds left right now, derived from a snapshot.
 *
 * Goes negative once the session runs past its blocks — overtime is a state the
 * floor must see, so it is never clamped at zero here (CountdownClock renders
 * the `overtime` variant off the sign).
 */
export function remainingSecondsNow(snapshot: ClockSnapshot, at: number = serverNow()): number {
  if (!snapshot.running) return snapshot.remainingSeconds;
  const asOf = toMillis(snapshot.asOf);
  if (asOf === null) return snapshot.remainingSeconds;
  const elapsed = (at - asOf) / 1000;
  return snapshot.remainingSeconds - elapsed;
}

/**
 * `1:24:03` past an hour, `24:03` under it, `−2:15` in overtime.
 *
 * Seconds are truncated toward zero so a fresh 30-minute block reads `30:00`
 * for its first tick rather than flashing `29:59`.
 */
export function formatCountdown(seconds: number): string {
  const negative = seconds < 0;
  const total = Math.floor(Math.abs(seconds));
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const secs = total % 60;
  const body =
    hours > 0
      ? `${hours}:${pad(minutes)}:${pad(secs)}`
      : `${minutes}:${pad(secs)}`;
  return negative ? `−${body}` : body;
}

/** "2 h 30 min" / "45 min" — durations in prose, not on a clock face. */
export function formatDuration(seconds: number): string {
  const total = Math.max(0, Math.round(seconds));
  const hours = Math.floor(total / 3600);
  const minutes = Math.round((total % 3600) / 60);
  if (hours === 0) return `${minutes} min`;
  if (minutes === 0) return `${hours} h`;
  return `${hours} h ${minutes} min`;
}

/** Seconds from now (server time) until an instant; negative once it is past. */
export function secondsUntil(instant: string | number | Date, at: number = serverNow()): number {
  const target = toMillis(instant);
  if (target === null) return 0;
  return (target - at) / 1000;
}

/** True once `instant` is in the past by the server's reckoning. */
export function isPast(instant: string | number | Date, at: number = serverNow()): boolean {
  return secondsUntil(instant, at) <= 0;
}

/** The venue-local calendar day of an instant, `YYYY-MM-DD`. */
export function venueDate(instant: string | number | Date = serverNow()): string {
  const ms = toMillis(instant);
  if (ms === null) return '';
  return dateFormatter.format(new Date(ms));
}

/** Today in the venue's timezone — what a token's `tokenDate` is compared to. */
export function venueToday(at: number = serverNow()): string {
  return venueDate(at);
}

/**
 * Whether a `YYYY-MM-DD` (a token's issue date) is the venue's current day.
 *
 * Tokens are identity, not payment proof, and the counter restarts every venue
 * midnight — a token dated before today is displayed with its date so two
 * "#04"s a day apart cannot be confused (§5.12, TokenBadge).
 */
export function isVenueToday(date: string, at: number = serverNow()): boolean {
  return date === venueToday(at);
}

/** `14:05` in venue time — topbar clock, booking start times, shift rows. */
export function formatVenueTime(instant: string | number | Date): string {
  const ms = toMillis(instant);
  if (ms === null) return '';
  return timeFormatter.format(new Date(ms));
}

/** `2 Sep, 14:05` in venue time — table cells that cross days. */
export function formatVenueDateTime(instant: string | number | Date): string {
  const ms = toMillis(instant);
  if (ms === null) return '';
  return `${dayFormatter.format(new Date(ms))}, ${formatVenueTime(ms)}`;
}

const dateFormatter = new Intl.DateTimeFormat('en-CA', {
  timeZone: VENUE_TIMEZONE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
});

const timeFormatter = new Intl.DateTimeFormat('en-GB', {
  timeZone: VENUE_TIMEZONE,
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
});

const dayFormatter = new Intl.DateTimeFormat('en-GB', {
  timeZone: VENUE_TIMEZONE,
  day: 'numeric',
  month: 'short',
});

function toMillis(value: string | number | Date | null | undefined): number | null {
  if (value === null || value === undefined) return null;
  if (typeof value === 'number') return Number.isFinite(value) ? value : null;
  const ms = value instanceof Date ? value.getTime() : Date.parse(value);
  return Number.isNaN(ms) ? null : ms;
}

function pad(value: number): string {
  return String(value).padStart(2, '0');
}

/* ------------------------------------------------ the venue's wall clock */

/**
 * How far the venue's timezone is ahead of UTC at an instant, in minutes.
 *
 * Worked out by formatting the instant in venue time and reading the result
 * back as if it were UTC — the standard trick, and the only one that stays
 * right across a DST rule this app does not hard-code (Dhaka has none today;
 * that is a fact about the timezone database, not something to bake in).
 */
export function venueOffsetMinutes(at: number = serverNow()): number {
  const parts = partsFormatter.formatToParts(new Date(at));
  const read = (type: Intl.DateTimeFormatPartTypes) =>
    Number(parts.find((part) => part.type === type)?.value ?? '0');
  const asUtc = Date.UTC(
    read('year'),
    read('month') - 1,
    read('day'),
    read('hour') % 24,
    read('minute'),
    read('second'),
  );
  return Math.round((asUtc - at) / 60_000);
}

/** `+06:00` — the offset a venue-local wall time has to be written with. */
export function venueOffset(at: number = serverNow()): string {
  const minutes = venueOffsetMinutes(at);
  const sign = minutes < 0 ? '-' : '+';
  const abs = Math.abs(minutes);
  return `${sign}${pad(Math.floor(abs / 60))}:${pad(abs % 60)}`;
}

/**
 * `2026-09-04T21:00` in venue time — the value a `datetime-local` input wants.
 *
 * The booking form's start-time picker is the one place this app has to put a
 * wall-clock reading *into* a control rather than take one out, and the reading
 * has to be the venue's: a terminal left on some other timezone must still
 * offer "tonight at nine" as 21:00.
 */
export function venueLocalInput(instant: string | number | Date = serverNow()): string {
  const ms = toMillis(instant);
  if (ms === null) return '';
  const parts = partsFormatter.formatToParts(new Date(ms));
  const read = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((part) => part.type === type)?.value ?? '00';
  const hour = read('hour') === '24' ? '00' : read('hour');
  return `${read('year')}-${read('month')}-${read('day')}T${hour}:${read('minute')}`;
}

/**
 * The other direction: a `datetime-local` value read as venue wall time, turned
 * into the absolute instant the API takes (`2026-09-04T21:00:00+06:00`).
 *
 * Two passes, because the offset depends on the instant it is being asked
 * about: guess by reading the text as UTC, then re-ask at the guessed instant.
 * `null` for anything that is not a complete `YYYY-MM-DDTHH:mm`.
 */
export function instantFromVenueLocal(input: string): string | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/.exec(input.trim());
  if (!match) return null;
  const asUtc = Date.parse(`${input}:00Z`);
  if (Number.isNaN(asUtc)) return null;
  const guess = asUtc - venueOffsetMinutes(asUtc) * 60_000;
  const offset = venueOffset(guess);
  return `${input}:00${offset}`;
}

const partsFormatter = new Intl.DateTimeFormat('en-GB', {
  timeZone: VENUE_TIMEZONE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
});
