'use client';

/**
 * The shell's topbar: screen title, occupancy, wall clock, sync chip
 * (design.md §1 shell / prototype header).
 *
 * The clock is the venue's, read through `lib/time.ts` — the same server
 * offset every countdown ticks from (frontend/ARCHITECTURE.md §5.2). A
 * terminal whose Windows clock is ten minutes out still shows the venue's
 * time here, which matters because staff read shift and booking times off it.
 */

import { useEffect, useState } from 'react';
import { SyncChip } from './sync-chip';
import { formatVenueTime, serverNow } from '@/lib/time';
import type { Occupancy } from '@/features/sessions/queries';
import type { SyncChipStatus } from '@/features/sync/use-sync-status';

export type TopBarProps = {
  title: string;
  occupancy: Occupancy;
  /** `['sync']`, read by the shell — the chip stays presentational. */
  sync?: SyncChipStatus;
};

export function TopBar({ title, occupancy, sync }: TopBarProps) {
  const clock = useVenueClock();

  return (
    <header className="flex h-[70px] flex-none items-center gap-5 border-b-2 border-divider px-6">
      <h1 className="truncate text-h2">{title}</h1>

      <div className="ml-auto flex items-stretch gap-0">
        <div className="flex flex-col justify-center px-5">
          <p className="type-label opacity-55">Stations busy</p>
          <p className="font-heading text-[20px] font-extrabold tabular">
            {occupancy.total > 0 ? `${occupancy.busy}/${occupancy.total}` : '—'}
          </p>
        </div>

        <div className="flex flex-col justify-center border-l-2 border-divider px-5">
          <p className="type-label opacity-55">Clock</p>
          <p data-testid="venue-clock" className="font-heading text-[20px] font-extrabold tabular">
            {clock || '--:--'}
          </p>
        </div>

        <div className="flex items-center border-l-2 border-divider pl-5">
          <SyncChip state={sync?.state} lastSyncedAt={sync?.lastSyncedAt} />
        </div>
      </div>
    </header>
  );
}

/**
 * `14:05` in venue time. Empty on the first render so the server-rendered HTML
 * and the client's first paint agree — the clock is the one thing on the page
 * that cannot match across a hydration boundary.
 *
 * It wakes on the minute rather than the second, because that is how often the
 * reading changes; re-rendering the topbar sixty times for one visible change
 * is work the floor screen would rather have.
 */
function useVenueClock(): string {
  const [now, setNow] = useState('');

  useEffect(() => {
    let timer: ReturnType<typeof setTimeout>;
    const tick = () => {
      const at = serverNow();
      setNow(formatVenueTime(at));
      // Land just after the next minute boundary, whatever the offset is.
      timer = setTimeout(tick, 60_000 - (at % 60_000) + 50);
    };
    tick();
    return () => clearTimeout(timer);
  }, []);

  return now;
}
