'use client';

/**
 * The persistent sync chip (design.md §1, "Global"): synced / syncing /
 * offline since HH:MM.
 *
 * Placeholder in F04 — the shell has to carry it from the first screen, but
 * the `['sync']` query and the SSE that keeps it honest are F05. It renders
 * the state it is given and defaults to the quiet one, so wiring it later is
 * one prop.
 *
 * Offline is never an error here: the browser talks to the venue box, not the
 * cloud, and everything keeps working (frontend/ARCHITECTURE.md §5.8).
 */

import { Check, RefreshCw, WifiOff } from 'lucide-react';
import { cn } from '@/components/ui';
import { formatVenueTime } from '@/lib/time';

export const SYNC_STATES = ['synced', 'syncing', 'offline'] as const;
export type SyncState = (typeof SYNC_STATES)[number];

export type SyncChipProps = {
  state?: SyncState;
  /** When the cloud was last reached — the "offline since HH:MM" timestamp. */
  lastSyncedAt?: string | number | Date | null;
  className?: string;
};

export function SyncChip({ state = 'synced', lastSyncedAt, className }: SyncChipProps) {
  const Icon = state === 'offline' ? WifiOff : state === 'syncing' ? RefreshCw : Check;
  const since = lastSyncedAt ? formatVenueTime(lastSyncedAt) : null;
  const label =
    state === 'offline'
      ? since
        ? `Offline since ${since}`
        : 'Offline'
      : state === 'syncing'
        ? 'Syncing'
        : 'Synced';

  return (
    <span
      data-testid="sync-chip"
      data-state={state}
      title={label}
      className={cn(
        'inline-flex items-center gap-1.5 border border-divider px-2 py-1 text-[11px] leading-none',
        state === 'offline' ? 'text-accent-strong' : 'opacity-70',
        className,
      )}
    >
      <Icon
        aria-hidden="true"
        className={cn('size-3.5 shrink-0', state === 'syncing' && 'animate-spin')}
        strokeWidth={2}
      />
      <span className="tabular">{label}</span>
    </span>
  );
}
