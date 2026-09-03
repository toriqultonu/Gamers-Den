'use client';

/**
 * Play-queue writes. F06 needs one of them: **seat**.
 *
 * `POST /play-queue/{id}/seat {stationId}` is one server transaction — the
 * session, its prepaid blocks carrying the original sale's transaction, the
 * token to SEATED, and (when the token came from a pre-booking) the booking to
 * USED. The clock starts when staff press start; extra time is ordinary
 * billable +30 blocks (docs/bookings.md §2–3).
 *
 * **Never optimistic** (frontend/ARCHITECTURE.md §5.3). A seat spends a paid
 * token and the server can still say no — `CONSOLE_TYPE_MISMATCH` for a PS5
 * ticket aimed at a PS4, `STATION_BUSY` for a console someone reached first,
 * `STATION_RESERVED` while a tournament holds it. The rail disables what it
 * can see coming and renders the notice for what it cannot.
 *
 * Selling tickets and refunding no-shows land with the POS (F07) and the
 * bookings desk (F10); this module grows there.
 */

import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api, type Schemas } from '@/lib/api';
import { queryKeys } from '@/lib/query-keys';

export type QueueEntrySeated = Schemas['QueueEntrySeated'];

export type SeatInput = {
  entryId: number;
  stationId: number;
};

export function useSeatQueueEntry() {
  const client = useQueryClient();

  return useMutation({
    mutationFn: ({ entryId, stationId }: SeatInput) =>
      api.post<QueueEntrySeated>(`/play-queue/${entryId}/seat`, { stationId }),

    onSuccess: (seated) => {
      void client.invalidateQueries({ queryKey: queryKeys.queue.all() });
      void client.invalidateQueries({ queryKey: queryKeys.stations.all() });
      // A seated booking leaves the Upcoming tab for History.
      void client.invalidateQueries({ queryKey: ['bookings'] });
      const sessionId = seated.session?.id;
      if (typeof sessionId === 'number') {
        void client.invalidateQueries({ queryKey: queryKeys.sessions.detail(sessionId) });
      } else {
        void client.invalidateQueries({ queryKey: queryKeys.sessions.all() });
      }
    },
  });
}
