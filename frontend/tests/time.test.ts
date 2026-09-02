/**
 * The server-offset clock — frontend/ARCHITECTURE.md §5.2 ("never local
 * wall-clock") and §5.12 (tokens roll over at venue midnight).
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  formatCountdown,
  formatDuration,
  formatVenueTime,
  hasServerTime,
  isPast,
  isVenueToday,
  noteServerTime,
  remainingSecondsNow,
  resetServerTime,
  secondsUntil,
  serverNow,
  serverOffsetMs,
  venueDate,
  venueToday,
} from '@/lib/time';

/** A terminal whose own clock reads 12:00:00 UTC. */
const LOCAL_NOW = Date.parse('2026-09-02T12:00:00Z');

beforeEach(() => {
  resetServerTime();
  vi.useFakeTimers();
  vi.setSystemTime(LOCAL_NOW);
});

afterEach(() => {
  vi.useRealTimers();
  resetServerTime();
});

describe('server offset', () => {
  it('starts at zero and reports that nothing has been measured', () => {
    expect(hasServerTime()).toBe(false);
    expect(serverOffsetMs()).toBe(0);
    expect(serverNow()).toBe(LOCAL_NOW);
  });

  it('measures how far the terminal clock is off', () => {
    // The server says 12:10 while this box says 12:00 — it is ten minutes slow.
    noteServerTime('2026-09-02T12:10:00Z');
    expect(hasServerTime()).toBe(true);
    expect(serverOffsetMs()).toBe(600_000);
    expect(serverNow()).toBe(Date.parse('2026-09-02T12:10:00Z'));
  });

  it('handles a terminal running fast just the same', () => {
    noteServerTime('2026-09-02T11:55:00Z');
    expect(serverOffsetMs()).toBe(-300_000);
    expect(serverNow()).toBe(Date.parse('2026-09-02T11:55:00Z'));
  });

  it('ignores a header the server did not send', () => {
    noteServerTime('2026-09-02T12:10:00Z');
    noteServerTime(null);
    noteServerTime('not a date');
    expect(serverOffsetMs()).toBe(600_000);
  });

  it('reads the RFC-1123 Date header shape verbatim', () => {
    noteServerTime('Wed, 02 Sep 2026 12:10:00 GMT');
    expect(serverNow()).toBe(Date.parse('2026-09-02T12:10:00Z'));
  });
});

describe('countdown math', () => {
  const asOf = '2026-09-02T12:10:00Z'; // server time of the reading

  it('drains a running clock in real time', () => {
    noteServerTime(asOf);
    const snapshot = { remainingSeconds: 1800, asOf, running: true };

    expect(remainingSecondsNow(snapshot)).toBe(1800);

    vi.setSystemTime(LOCAL_NOW + 60_000);
    expect(remainingSecondsNow(snapshot)).toBe(1740);

    vi.setSystemTime(LOCAL_NOW + 90_500);
    expect(remainingSecondsNow(snapshot)).toBeCloseTo(1709.5, 3);
  });

  it('ticks off the server clock, not the terminal one', () => {
    // The terminal is ten minutes fast. Naive `Date.now() - asOf` math would
    // show 20 minutes left on a block that still has 30.
    noteServerTime('2026-09-02T11:50:00Z');
    const snapshot = { remainingSeconds: 1800, asOf: '2026-09-02T11:50:00Z', running: true };
    expect(remainingSecondsNow(snapshot)).toBe(1800);

    vi.setSystemTime(LOCAL_NOW + 300_000);
    expect(remainingSecondsNow(snapshot)).toBe(1500);
  });

  it('holds a paused clock at its reading', () => {
    noteServerTime(asOf);
    const snapshot = { remainingSeconds: 1234, asOf, running: false };
    vi.setSystemTime(LOCAL_NOW + 10 * 60_000);
    expect(remainingSecondsNow(snapshot)).toBe(1234);
  });

  it('goes negative in overtime rather than clamping at zero', () => {
    noteServerTime(asOf);
    const snapshot = { remainingSeconds: 30, asOf, running: true };
    vi.setSystemTime(LOCAL_NOW + 150_000);
    expect(remainingSecondsNow(snapshot)).toBe(-120);
  });

  it('re-bases on a fresh snapshot — a +5 min match extend', () => {
    noteServerTime(asOf);
    vi.setSystemTime(LOCAL_NOW + 60_000);
    const extended = { remainingSeconds: 600, asOf: serverNow(), running: true };
    expect(remainingSecondsNow(extended)).toBe(600);
  });
});

describe('formatting', () => {
  it('renders a clock face, hours only when there are any', () => {
    expect(formatCountdown(1800)).toBe('30:00');
    expect(formatCountdown(1799.9)).toBe('29:59');
    expect(formatCountdown(5043)).toBe('1:24:03');
    expect(formatCountdown(9)).toBe('0:09');
  });

  it('marks overtime with a minus', () => {
    expect(formatCountdown(-120)).toBe('−2:00');
    expect(formatCountdown(-3661)).toBe('−1:01:01');
  });

  it('spells durations in prose', () => {
    expect(formatDuration(1800)).toBe('30 min');
    expect(formatDuration(3600)).toBe('1 h');
    expect(formatDuration(5400)).toBe('1 h 30 min');
  });

  it('prints venue wall-clock time, whatever the browser timezone', () => {
    // 12:10 UTC is 18:10 in Asia/Dhaka.
    expect(formatVenueTime('2026-09-02T12:10:00Z')).toBe('18:10');
  });
});

describe('venue day rollover', () => {
  it('answers "today" in venue time, not UTC', () => {
    // 20:00 UTC on the 2nd is already 02:00 on the 3rd in Dhaka.
    expect(venueDate('2026-09-02T20:00:00Z')).toBe('2026-09-03');
    expect(venueDate('2026-09-02T12:00:00Z')).toBe('2026-09-02');
  });

  it('dates a token against the venue day', () => {
    noteServerTime('2026-09-02T12:00:00Z');
    expect(venueToday()).toBe('2026-09-02');
    expect(isVenueToday('2026-09-02')).toBe(true);
    expect(isVenueToday('2026-09-01')).toBe(false);
  });

  it('rolls the day over at venue midnight, not at the terminal midnight', () => {
    noteServerTime('2026-09-02T17:59:00Z'); // 23:59 in Dhaka
    expect(venueToday()).toBe('2026-09-02');

    noteServerTime('2026-09-02T18:01:00Z'); // 00:01, next venue day
    expect(venueToday()).toBe('2026-09-03');
  });
});

describe('cutoffs', () => {
  it('counts down to an instant on server time', () => {
    noteServerTime('2026-09-02T12:10:00Z');
    expect(secondsUntil('2026-09-02T13:10:00Z')).toBe(3600);
    expect(secondsUntil('2026-09-02T12:00:00Z')).toBe(-600);
  });

  it('knows what is already past', () => {
    noteServerTime('2026-09-02T12:10:00Z');
    expect(isPast('2026-09-02T12:09:59Z')).toBe(true);
    expect(isPast('2026-09-02T12:10:01Z')).toBe(false);
  });
});
