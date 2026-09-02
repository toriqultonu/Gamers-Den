'use client';

/**
 * The client boundary that owns the query cache for the whole app.
 *
 * Server components stay server components above this: the provider wraps
 * `children` without reading them, so a page only becomes client-side when it
 * actually asks for a hook (frontend/ARCHITECTURE.md §5.1).
 */

import { QueryClientProvider } from '@tanstack/react-query';
import { getQueryClient } from './query-client';

export function QueryProvider({ children }: { children: React.ReactNode }) {
  const queryClient = getQueryClient();
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}
