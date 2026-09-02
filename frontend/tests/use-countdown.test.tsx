/**
 * useCountdown — the tick every clock on the floor shares
 * (frontend/ARCHITECTURE.md §5.2).
 */

import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useCountdown } from '@/lib/use-countdown';
import { noteServerTime, resetServerTime } from '@/lib/time';

const LOCAL_NOW = Date.parse('2026-09-02T12:00:00Z');

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(LOCAL_NOW);
  resetServerTime();
});

afterEach(() => {
  vi.useRealTimers();
  resetServerTime();
});

/** Advance both the wall clock the hook reads and its interval. */
function advance(ms: number) {
  act(() => {
    vi.advanceTimersByTime(ms);
  });
}

describe('useCountdown', () => {
  it('counts a running clock down every second', () => {
    const asOf = '2026-09-02T12:00:00Z';
    noteServerTime(asOf);

    const { result } = renderHook(() =>
      useCountdown({ remainingSeconds: 1800, asOf, running: true }),
    );

    expect(result.current).toBe(1800);
    advance(1000);
    expect(result.current).toBe(1799);
    advance(59_000);
    expect(result.current).toBe(1740);
  });

  it('holds a paused clock still', () => {
    const asOf = '2026-09-02T12:00:00Z';
    noteServerTime(asOf);

    const { result } = renderHook(() =>
      useCountdown({ remainingSeconds: 900, asOf, running: false }),
    );

    advance(30_000);
    expect(result.current).toBe(900);
  });

  it('reads the server clock, not the terminal one', () => {
    // Terminal ten minutes fast; the reading is from server 12:00.
    noteServerTime('2026-09-02T11:50:00Z');

    const { result } = renderHook(() =>
      useCountdown({ remainingSeconds: 1800, asOf: '2026-09-02T11:50:00Z', running: true }),
    );

    expect(result.current).toBe(1800);
    advance(60_000);
    expect(result.current).toBe(1740);
  });

  it('re-bases when a fresh snapshot arrives — a +5 min match extend', () => {
    const asOf = '2026-09-02T12:00:00Z';
    noteServerTime(asOf);

    const { result, rerender } = renderHook(
      ({ remainingSeconds }: { remainingSeconds: number }) =>
        useCountdown({ remainingSeconds, asOf: '2026-09-02T12:00:00Z', running: true }),
      { initialProps: { remainingSeconds: 120 } },
    );

    advance(60_000);
    expect(result.current).toBe(60);

    rerender({ remainingSeconds: 420 });
    expect(result.current).toBe(360);
  });

  it('runs into overtime rather than stopping at zero', () => {
    const asOf = '2026-09-02T12:00:00Z';
    noteServerTime(asOf);

    const { result } = renderHook(() =>
      useCountdown({ remainingSeconds: 30, asOf, running: true }),
    );

    advance(90_000);
    expect(result.current).toBe(-60);
  });

  it('has no clock at all for a free console', () => {
    const { result } = renderHook(() => useCountdown(null));
    expect(result.current).toBeNull();
  });
});
