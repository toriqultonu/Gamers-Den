'use client';

/**
 * A value that lags behind the one it is given, by `delay` milliseconds of
 * quiet.
 *
 * The member directory is the reason it exists: `GET /members?q=` runs a LIKE
 * over name and phone, and a cashier typing a phone number would otherwise
 * fire eleven searches for one customer. Debouncing the *query key* rather
 * than the request means the cache holds one entry per settled search instead
 * of one per keystroke — the request that was never worth making is never
 * made, rather than made and thrown away.
 *
 * The typed value itself is never debounced: the input stays exactly as
 * responsive as the keyboard. Only what the cache is asked for waits.
 */

import { useEffect, useState } from 'react';

export function useDebouncedValue<T>(value: T, delay = 250): T {
  const [settled, setSettled] = useState(value);

  useEffect(() => {
    if (delay <= 0) {
      setSettled(value);
      return;
    }
    const timer = setTimeout(() => setSettled(value), delay);
    return () => clearTimeout(timer);
  }, [value, delay]);

  return settled;
}
