'use client';

/**
 * Auto-lock — S13's "Auto-lock (PIN to unlock): Off / 2 / 5 / 10 min".
 *
 * The counter PC stands in a public room; a cashier who walks away should not
 * leave the till, the member list and the drawer count facing it. After
 * `minutes` with no operator input the shell drops a lock over the app and
 * asks for the PIN again — the session itself survives, so nothing in flight
 * is lost.
 *
 * Activity is *operator* activity: pointer, keyboard, wheel, touch. Background
 * SSE traffic and ticking clocks are not someone standing there, so they must
 * not hold the terminal open.
 */

import { useEffect, useRef } from 'react';

/** Off, or the S13 choices. `0` (and anything below) means never lock. */
export const AUTO_LOCK_CHOICES = [0, 2, 5, 10] as const;

const ACTIVITY_EVENTS = [
  'pointerdown',
  'keydown',
  'wheel',
  'touchstart',
] as const satisfies readonly (keyof WindowEventMap)[];

/** Re-arming on every single event would be pointless churn at 60 Hz. */
const REARM_THROTTLE_MS = 1000;

export type AutoLockOptions = {
  /** Minutes of idle before locking; `0` disables it. */
  minutes: number;
  /** Off while already locked, or before anyone is signed in. */
  enabled?: boolean;
  onLock: () => void;
};

export function useAutoLock({ minutes, enabled = true, onLock }: AutoLockOptions): void {
  const fire = useRef(onLock);
  fire.current = onLock;

  useEffect(() => {
    if (!enabled || minutes <= 0) return;
    if (typeof window === 'undefined') return;

    const idleMs = minutes * 60_000;
    let timer = window.setTimeout(() => fire.current(), idleMs);
    let armedAt = Date.now();

    const rearm = () => {
      const now = Date.now();
      if (now - armedAt < REARM_THROTTLE_MS) return;
      armedAt = now;
      window.clearTimeout(timer);
      timer = window.setTimeout(() => fire.current(), idleMs);
    };

    for (const event of ACTIVITY_EVENTS) {
      window.addEventListener(event, rearm, { passive: true });
    }

    return () => {
      window.clearTimeout(timer);
      for (const event of ACTIVITY_EVENTS) window.removeEventListener(event, rearm);
    };
  }, [enabled, minutes]);
}
