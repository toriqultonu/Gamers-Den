/**
 * The canonical query keys — frontend/ARCHITECTURE.md §4.1, verbatim.
 *
 * They are written down once because two things have to agree on them: the
 * queries that read server state, and the SSE handlers (F05) that push
 * `station-update` / `queue-update` / `booking-update` straight into the cache.
 * A hand-typed array in a component is how those two silently stop matching.
 */

export type BookingsTab = 'upcoming' | 'history';

export const queryKeys = {
  sessions: {
    all: () => ['sessions'] as const,
    detail: (id: number | string) => ['sessions', id] as const,
    bill: (id: number | string) => ['sessions', id, 'bill'] as const,
  },
  stations: {
    all: () => ['stations'] as const,
  },
  items: {
    all: () => ['items'] as const,
  },
  /**
   * The rate card. §4.1 lists the keys SSE writes into and pricing is not one
   * of them, but §5.11 has the client pricing its previews "from cached
   * pricing" — the POS play-ticket cards and S14's bill box both need it in
   * the cache, so it gets the obvious namespaced key here rather than a
   * hand-typed array in two screens (F07; called out in the task notes).
   */
  pricing: {
    all: () => ['pricing'] as const,
  },
  /**
   * The staff roster S10 edits (`GET /staff`, Admin). §4.1 does not name it —
   * it lists the keys SSE writes into, and nothing pushes staff — but the
   * roster is server state read by a screen, so it gets a namespaced key here
   * rather than a hand-typed array in the setup screen (F13).
   */
  staff: {
    all: () => ['staff'] as const,
  },
  members: {
    search: (query: string) => ['members', query] as const,
    detail: (id: number | string) => ['members', id] as const,
  },
  bookings: {
    tab: (tab: BookingsTab) => ['bookings', tab] as const,
    detail: (id: number | string) => ['bookings', id] as const,
    settings: () => ['booking-settings'] as const,
  },
  queue: {
    all: () => ['queue'] as const,
  },
  tournaments: {
    all: () => ['tournaments'] as const,
    detail: (id: number | string) => ['tournaments', id] as const,
    finance: (id: number | string) => ['tournaments', id, 'finance'] as const,
    /**
     * The Done/called-off list behind S12's History tab (`GET
     * /tournaments/history`). §4.1 names the three keys above — the live list,
     * one event, its finances — and history is a fourth read with a different
     * lifetime: it changes only when an event finishes. It nests under
     * `['tournaments']` so one invalidation after a final still refreshes it
     * (F11; called out in the task notes).
     */
    history: () => ['tournaments', 'history'] as const,
    /**
     * The match board with its console availability (`GET
     * /tournaments/{id}/matches`). The bracket comes with the detail; what only
     * this read carries is *why* each allocated console is or is not free, which
     * is what the start button explains — so it nests under the event exactly as
     * `['sessions', id, 'bill']` nests under a session (F11).
     */
    board: (id: number | string) => ['tournaments', id, 'matches'] as const,
  },
  shift: {
    current: () => ['shift', 'current'] as const,
  },
  expenses: {
    all: () => ['expenses'] as const,
  },
  reports: {
    range: (range: string) => ['reports', range] as const,
  },
  printJobs: {
    detail: (id: number | string) => ['print-jobs', id] as const,
    /**
     * The stored 48-column render. §4.1 names `['print-jobs', id]`; the render
     * is a second read of the same job with the opposite lifetime — the status
     * is polled while the paper moves, the render never changes at all — so it
     * nests under it exactly as `['sessions', id, 'bill']` nests under the
     * session (F08; called out in the task notes).
     */
    render: (id: number | string) => ['print-jobs', id, 'render'] as const,
  },
  printers: {
    all: () => ['printers'] as const,
  },
  terminalSettings: {
    all: () => ['terminal-settings'] as const,
  },
  sync: {
    status: () => ['sync'] as const,
  },
  alerts: {
    all: () => ['alerts'] as const,
  },
} as const;

/** Every key this app is allowed to use, for the drift test. */
export type QueryKey =
  | ReturnType<typeof queryKeys.sessions.all>
  | ReturnType<typeof queryKeys.sessions.detail>
  | ReturnType<typeof queryKeys.sessions.bill>
  | ReturnType<typeof queryKeys.stations.all>
  | ReturnType<typeof queryKeys.items.all>
  | ReturnType<typeof queryKeys.pricing.all>
  | ReturnType<typeof queryKeys.staff.all>
  | ReturnType<typeof queryKeys.members.search>
  | ReturnType<typeof queryKeys.members.detail>
  | ReturnType<typeof queryKeys.bookings.tab>
  | ReturnType<typeof queryKeys.bookings.detail>
  | ReturnType<typeof queryKeys.bookings.settings>
  | ReturnType<typeof queryKeys.queue.all>
  | ReturnType<typeof queryKeys.tournaments.all>
  | ReturnType<typeof queryKeys.tournaments.detail>
  | ReturnType<typeof queryKeys.tournaments.finance>
  | ReturnType<typeof queryKeys.tournaments.history>
  | ReturnType<typeof queryKeys.tournaments.board>
  | ReturnType<typeof queryKeys.shift.current>
  | ReturnType<typeof queryKeys.expenses.all>
  | ReturnType<typeof queryKeys.reports.range>
  | ReturnType<typeof queryKeys.printJobs.detail>
  | ReturnType<typeof queryKeys.printJobs.render>
  | ReturnType<typeof queryKeys.printers.all>
  | ReturnType<typeof queryKeys.terminalSettings.all>
  | ReturnType<typeof queryKeys.sync.status>
  | ReturnType<typeof queryKeys.alerts.all>;
