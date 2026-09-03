'use client';

/**
 * S3 — Floor. The screen the counter lives on.
 *
 * Three things side by side: the console grid, the play-queue rail beneath it,
 * and the session panel down the right. All of them read the same two live
 * keys — `['stations']` and `['queue']` — which SSE writes into directly, so a
 * ticket sold at the POS or a match started on S12 lands here without this
 * screen asking for anything (lib/sse.ts).
 *
 * All five states design.md §1 requires are here: default, a skeleton shaped
 * like the grid, "No stations — add one in Setup", an error banner over
 * disabled controls (an error never takes the floor away — the last known
 * cards stay on screen), and the access notice for a 403.
 *
 * **Selection is local.** `selectedStationId` belongs in the one Zustand store
 * (frontend/ARCHITECTURE.md §4.2), which F07 introduces along with the bill
 * draft; until then it is this component's own state, which behaves the same
 * for a single screen and resets on refresh exactly as the store would.
 */

import { useMemo, useState } from 'react';
import { AccessNotice } from './access-notice';
import { StationCard } from './station-card';
import { SessionPanel, type SeatOffer } from './session-panel';
import { QueueRail } from './queue-rail';
import { errorNotice, isApiError } from '@/lib/api';
import { venueToday } from '@/lib/time';
import {
  useSessionBill,
  useSessionDetail,
  useStations,
} from '@/features/sessions/queries';
import {
  useChangeBlocks,
  useClockAction,
  useEndSession,
  useStartSession,
  type ClockAction,
} from '@/features/sessions/mutations';
import { usePlayQueue, waitingEntries, type QueueEntry } from '@/features/queue/queries';
import { useSeatQueueEntry } from '@/features/queue/mutations';
import type { Station } from '@/features/sessions/schemas';

export function FloorScreen() {
  const stations = useStations();
  const queue = usePlayQueue();

  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [seatingEntryId, setSeatingEntryId] = useState<number | null>(null);

  const rows = stations.data ?? [];
  const selected = useMemo<Station | null>(() => {
    if (rows.length === 0) return null;
    return rows.find((station) => station.id === selectedId) ?? rows[0] ?? null;
  }, [rows, selectedId]);

  const sessionId = selected?.session?.id ?? null;
  const session = useSessionDetail(sessionId);
  const bill = useSessionBill(sessionId);

  const waiting = useMemo(() => waitingEntries(queue.data), [queue.data]);

  const fail = (error: unknown) => setNotice(errorNotice(error));
  const clear = () => setNotice(null);

  const startSession = useStartSession();
  const changeBlocks = useChangeBlocks();
  const clockAction = useClockAction();
  const endSession = useEndSession();
  const seatEntry = useSeatQueueEntry();

  // A 403 on the floor is the access notice, not a banner: the screen itself
  // is refused, and there is nothing behind it to keep showing.
  if (isApiError(stations.error) && stations.error.status === 403) {
    return <AccessNotice screen="Floor" />;
  }

  const readFailed = stations.isError;
  // Controls go off when the read failed, so nobody acts on a floor that may
  // already have moved (design.md §1, S3: "controls disabled + banner").
  const controlsOff = readFailed;

  const seat = (entryId: number, stationId: number) => {
    clear();
    setSeatingEntryId(entryId);
    seatEntry.mutate(
      { entryId, stationId },
      {
        onError: fail,
        onSuccess: () => setSelectedId(stationId),
        onSettled: () => setSeatingEntryId(null),
      },
    );
  };

  const onSeatOffer = (offer: SeatOffer) => {
    if (!selected?.id) return;
    seat(offer.queueEntryId, selected.id);
  };

  const onSeatFromRail = (entry: QueueEntry, stationId: number) => {
    if (typeof entry.id !== 'number') return;
    seat(entry.id, stationId);
  };

  const onStart = (station: Station) => {
    if (typeof station.id !== 'number') return;
    clear();
    startSession.mutate({ stationId: station.id }, { onError: fail });
  };

  const onBlocks = (delta: 1 | -1) => {
    if (sessionId === null) return;
    clear();
    // The cache moves now and the server reconciles; a `BLOCKS_CONSUMED`
    // refusal rolls it back and this notice explains why (mutations.ts).
    changeBlocks.mutate({ sessionId, delta }, { onError: fail });
  };

  const onClock = (action: ClockAction) => {
    if (sessionId === null) return;
    clear();
    clockAction.mutate({ sessionId, action }, { onError: fail });
  };

  const onEnd = () => {
    if (sessionId === null) return;
    clear();
    endSession.mutate(sessionId, { onError: fail });
  };

  const busy = startSession.isPending
    ? 'start'
    : changeBlocks.isPending
      ? 'blocks'
      : clockAction.isPending
        ? 'clock'
        : endSession.isPending
          ? 'end'
          : seatEntry.isPending
            ? 'seat'
            : null;

  return (
    <div data-testid="floor-screen" className="flex min-h-0 flex-1">
      <div className="flex min-w-0 flex-1 flex-col gap-4 overflow-auto p-5">
        {readFailed ? (
          <p role="alert" data-testid="floor-error" className="border-2 border-accent px-3 py-2 text-body text-accent-strong">
            {errorNotice(stations.error, 'The floor could not be read — showing the last known state.')}
          </p>
        ) : null}

        {stations.isPending ? (
          <FloorSkeleton />
        ) : rows.length === 0 ? (
          <p data-testid="floor-empty" className="text-body opacity-70">
            No stations — add one in Setup.
          </p>
        ) : (
          // design.md §4: 1-up below 1024 — the tablet reads one console at a time.
          <div className="grid grid-cols-2 gap-4 max-lg:grid-cols-1">
            {rows.map((station) => (
              <StationCard
                key={station.id}
                station={station}
                selected={selected?.id === station.id}
                receivedAt={stations.dataUpdatedAt}
                onSelect={(picked) => {
                  clear();
                  setSelectedId(picked.id ?? null);
                }}
              />
            ))}
          </div>
        )}

        <hr className="rule" />

        <QueueRail
          entries={waiting}
          stations={rows}
          onSeat={onSeatFromRail}
          seatingEntryId={seatingEntryId}
          preferStationId={selected?.id ?? null}
          today={venueToday()}
          disabled={controlsOff}
        />
      </div>

      <SessionPanel
        station={selected}
        session={session.data}
        bill={bill.data}
        waiting={waiting}
        receivedAt={stations.dataUpdatedAt}
        notice={notice}
        busy={busy}
        disabled={controlsOff}
        onStart={onStart}
        onBlocks={onBlocks}
        onClock={onClock}
        onEnd={onEnd}
        onSeat={onSeatOffer}
      />
    </div>
  );
}

/** The loading state, shaped like the grid it becomes (design.md §1). */
function FloorSkeleton() {
  return (
    <div
      data-testid="floor-skeleton"
      aria-busy="true"
      className="grid grid-cols-2 gap-4 max-lg:grid-cols-1"
    >
      {[0, 1, 2, 3].map((row) => (
        <div key={row} className="min-h-[210px] border-2 border-l-[10px] border-divider p-5">
          <div className="h-6 w-32 bg-track" />
          <div className="mt-4 h-12 w-40 bg-track" />
          <div className="mt-8 h-2 w-full bg-track" />
        </div>
      ))}
    </div>
  );
}
