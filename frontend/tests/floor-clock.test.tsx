/**
 * CountdownClock — the floor's clocks tick from `remainingSeconds` plus the
 * measured server offset, never from the terminal's own wall clock
 * (frontend/ARCHITECTURE.md §5.2, design.md §2).
 *
 * The offset is the whole point of these tests. A counter PC with a clock ten
 * minutes fast must show the same time left as the till beside it, so every
 * case here deliberately runs a wrong local clock and asserts the server's
 * answer — a component that quietly used `Date.now()` would be out by exactly
 * the offset and would pass nothing below.
 */

import { act, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CountdownClock, clockStateOf } from '@/components/domain/countdown-clock';
import { StationCard } from '@/components/domain/station-card';
import { stationClockSnapshot } from '@/features/sessions/schemas';
import type { Station } from '@/features/sessions/schemas';
import { noteServerTime, resetServerTime } from '@/lib/time';

/** The terminal's clock. */
const LOCAL_NOW = Date.parse('2026-09-02T12:00:00Z');
/** The server's — ten minutes ahead, which is the bug this guards against. */
const OFFSET_MS = 10 * 60_000;
const SERVER_NOW = LOCAL_NOW + OFFSET_MS;

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(LOCAL_NOW);
  resetServerTime();
  // What `lib/api.ts` does with every response's `Date` header.
  noteServerTime(new Date(SERVER_NOW).toISOString(), LOCAL_NOW);
});

afterEach(() => {
  vi.useRealTimers();
  resetServerTime();
});

function advance(ms: number) {
  act(() => {
    vi.advanceTimersByTime(ms);
  });
}

function clockText() {
  return screen.getByTestId('countdown-clock').textContent;
}

describe('the clock reads from remainingSeconds + the server offset', () => {
  it('starts at the server reading, not the local one', () => {
    render(
      <CountdownClock
        snapshot={{ remainingSeconds: 1800, asOf: SERVER_NOW, running: true }}
      />,
    );

    // A clock that trusted the local wall clock would read 20:00 here — the
    // reading is ten minutes "in the future" by this terminal's reckoning.
    expect(clockText()).toBe('30:00');
  });

  it('ticks down a second at a time while it runs', () => {
    render(
      <CountdownClock
        snapshot={{ remainingSeconds: 1800, asOf: SERVER_NOW, running: true }}
      />,
    );

    advance(1000);
    expect(clockText()).toBe('29:59');
    advance(59_000);
    expect(clockText()).toBe('29:00');
    expect(screen.getByTestId('countdown-clock')).toHaveAttribute('data-state', 'running');
  });

  it('holds a paused reading still', () => {
    render(
      <CountdownClock
        snapshot={{ remainingSeconds: 900, asOf: SERVER_NOW, running: false }}
      />,
    );

    expect(clockText()).toBe('15:00');
    advance(120_000);
    expect(clockText()).toBe('15:00');
    expect(screen.getByTestId('countdown-clock')).toHaveAttribute('data-state', 'paused');
  });

  it('goes into overtime rather than clamping at zero', () => {
    render(
      <CountdownClock snapshot={{ remainingSeconds: 5, asOf: SERVER_NOW, running: true }} />,
    );

    advance(20_000);
    expect(clockText()).toBe('−0:15');
    expect(screen.getByTestId('countdown-clock')).toHaveAttribute('data-state', 'overtime');
  });

  it('renders the none state for a console with no clock at all', () => {
    render(<CountdownClock snapshot={null} />);
    expect(clockText()).toBe('--:--');
    expect(screen.getByTestId('countdown-clock')).toHaveAttribute('data-state', 'none');
  });

  it('carries the three design.md sizes', () => {
    const { rerender } = render(<CountdownClock variant="panel" snapshot={null} />);
    expect(screen.getByTestId('countdown-clock')).toHaveAttribute('data-variant', 'panel');
    rerender(<CountdownClock variant="card" snapshot={null} />);
    expect(screen.getByTestId('countdown-clock')).toHaveAttribute('data-variant', 'card');
    rerender(<CountdownClock variant="match" snapshot={null} />);
    expect(screen.getByTestId('countdown-clock')).toHaveAttribute('data-variant', 'match');
  });
});

describe('clockStateOf', () => {
  it('maps a reading to the design.md state', () => {
    expect(clockStateOf(null, true)).toBe('none');
    expect(clockStateOf(undefined, true)).toBe('none');
    expect(clockStateOf(-1, true)).toBe('overtime');
    expect(clockStateOf(-1, false)).toBe('overtime');
    expect(clockStateOf(60, true)).toBe('running');
    expect(clockStateOf(60, false)).toBe('paused');
  });
});

describe('stationClockSnapshot', () => {
  const running: Station = {
    id: 1,
    name: 'Station 01',
    consoleType: 'PS5',
    floorState: 'RUNNING',
    session: { id: 41, blocks: 2, paidBlocks: 0, remainingSeconds: 1800, state: 'RUNNING' },
  };

  it('dates the card reading by when the list landed, in server time', () => {
    const snapshot = stationClockSnapshot(running, LOCAL_NOW);
    expect(snapshot).toEqual({ remainingSeconds: 1800, asOf: SERVER_NOW, running: true });
  });

  it('gives a free console no clock at all', () => {
    expect(
      stationClockSnapshot({ id: 2, floorState: 'FREE' } as Station, LOCAL_NOW),
    ).toBeNull();
  });

  it('ticks a started match like any other session', () => {
    const reserved: Station = {
      id: 3,
      name: 'Station 03',
      consoleType: 'PS5',
      floorState: 'RESERVED',
      match: { matchId: 9, remainingSeconds: 600, playerA: 'Rahim', playerB: 'Karim' },
    };
    expect(stationClockSnapshot(reserved, LOCAL_NOW)).toEqual({
      remainingSeconds: 600,
      asOf: SERVER_NOW,
      running: true,
    });
  });

  it('drives the card the operator actually looks at', () => {
    render(<StationCard station={running} receivedAt={LOCAL_NOW} />);
    expect(clockText()).toBe('30:00');
    advance(60_000);
    expect(clockText()).toBe('29:00');
  });
});
