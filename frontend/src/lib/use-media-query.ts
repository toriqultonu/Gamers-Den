'use client';

/**
 * A CSS media query, read as React state.
 *
 * design.md §4 gives two behaviours that CSS alone cannot express, because they
 * are about *initial state* rather than layout: between 1024 and 1279 the POS
 * ticket column "collapses behind a Preview button" and the Overview alerts
 * rail "starts collapsed". Starting collapsed is a default the operator can
 * then override, so it has to be a value the component can read — not a class
 * that hides a panel whose open flag says otherwise.
 *
 * `useSyncExternalStore` rather than an effect: the first client render already
 * has the right answer, so the rail never paints open and then snaps shut.
 *
 * The server snapshot is `false` — the counter terminal is 1440×900 and the
 * queries are all `min-width`, so the SSR pass assumes the narrow case and the
 * hydration pass widens it. Environments with no `matchMedia` at all (jsdom
 * without a stub) report `false` too, which is the same safe answer.
 */

import { useCallback, useSyncExternalStore } from 'react';

export function useMediaQuery(query: string): boolean {
  const subscribe = useCallback(
    (onChange: () => void) => {
      const list = matchMediaOrNull(query);
      if (!list) return () => {};
      list.addEventListener('change', onChange);
      return () => list.removeEventListener('change', onChange);
    },
    [query],
  );

  const getSnapshot = useCallback(() => matchesMediaQuery(query), [query]);

  return useSyncExternalStore(subscribe, getSnapshot, () => false);
}

/** design.md §4: full three-column layouts start here. */
export const WIDE_VIEWPORT = '(min-width: 1280px)';

function matchMediaOrNull(query: string): MediaQueryList | null {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return null;
  const list = window.matchMedia(query);
  // A hand-rolled test stub may return a plain object; without listeners there
  // is nothing to subscribe to, and the snapshot alone is still correct.
  return typeof list?.addEventListener === 'function' ? list : null;
}

/** The snapshot without a subscription — for a one-shot read outside render. */
export function matchesMediaQuery(query: string): boolean {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return false;
  return window.matchMedia(query)?.matches ?? false;
}
