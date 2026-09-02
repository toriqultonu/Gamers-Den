'use client';

/**
 * The tick behind every countdown on the floor — session blocks and tournament
 * match clocks alike (frontend/ARCHITECTURE.md §5.2).
 *
 * It owns no time of its own: each tick re-derives the remainder from the
 * server's snapshot plus the measured offset, so a drifting terminal clock
 * cannot accumulate error, and a new snapshot (an SSE `tournament-update`
 * carrying +5 min, a resumed session) re-bases the display on its next render.
 */

import { useEffect, useState } from 'react';
import { remainingSecondsNow, serverNow, type ClockSnapshot } from './time';

/**
 * Seconds left, recomputed every `intervalMs`. Negative once the session runs
 * into overtime — CountdownClock renders that state rather than hiding it.
 *
 * Returns `null` for no clock at all (a free console), which is the
 * CountdownClock `none` variant.
 */
export function useCountdown(
  snapshot: ClockSnapshot | null | undefined,
  intervalMs = 1000,
): number | null {
  const [seconds, setSeconds] = useState<number | null>(() =>
    snapshot ? remainingSecondsNow(snapshot) : null,
  );

  // The reading's own fields are the dependency, not the object: a re-render
  // that hands over an equal snapshot must not restart the timer.
  const { remainingSeconds, asOf, running } = snapshot ?? {};

  useEffect(() => {
    if (remainingSeconds === undefined || asOf === undefined) {
      setSeconds(null);
      return;
    }
    const reading: ClockSnapshot = { remainingSeconds, asOf, running: Boolean(running) };
    const read = () => setSeconds(remainingSecondsNow(reading, serverNow()));
    read();
    // A held clock (OPEN, PAUSED, LOCKED) has nothing to tick — re-reading it
    // every second would only re-render the floor for no change.
    if (!reading.running) return;
    const timer = setInterval(read, intervalMs);
    return () => clearInterval(timer);
  }, [remainingSeconds, asOf, running, intervalMs]);

  return seconds;
}
