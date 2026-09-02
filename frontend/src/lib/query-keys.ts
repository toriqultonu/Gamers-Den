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
  | ReturnType<typeof queryKeys.members.search>
  | ReturnType<typeof queryKeys.members.detail>
  | ReturnType<typeof queryKeys.bookings.tab>
  | ReturnType<typeof queryKeys.bookings.detail>
  | ReturnType<typeof queryKeys.bookings.settings>
  | ReturnType<typeof queryKeys.queue.all>
  | ReturnType<typeof queryKeys.tournaments.all>
  | ReturnType<typeof queryKeys.tournaments.detail>
  | ReturnType<typeof queryKeys.tournaments.finance>
  | ReturnType<typeof queryKeys.shift.current>
  | ReturnType<typeof queryKeys.expenses.all>
  | ReturnType<typeof queryKeys.reports.range>
  | ReturnType<typeof queryKeys.printJobs.detail>
  | ReturnType<typeof queryKeys.printers.all>
  | ReturnType<typeof queryKeys.terminalSettings.all>
  | ReturnType<typeof queryKeys.sync.status>
  | ReturnType<typeof queryKeys.alerts.all>;
