'use client';

/**
 * The three reads S2 and S9 run: `['overview']`, `['reports', range]`,
 * `['alerts']`.
 *
 * None of them is a live feed. The overview and the report are folded per
 * request from grouped reads — a void or a refund shows up on the next read, so
 * the honest cache policy is a short `staleTime` with a refetch when the
 * operator comes back to the tab, not a subscription. The alerts feed is the
 * exception: `lib/sse.ts` writes the `alert` event straight into `['alerts']`,
 * so the rail moves the moment a till closes short.
 *
 * The role guards are the API's (`GET /overview` Admin, `GET /reports`
 * Manager+). The screens hide themselves too, but hiding is cosmetic — a 403
 * that lands anyway renders as the access notice (design.md §1).
 */

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { queryKeys } from '@/lib/query-keys';
import {
  DEFAULT_RANGE,
  rangeParams,
  type Alert,
  type Overview,
  type RangeId,
  type Report,
} from './schemas';

/**
 * A report is a fold over a whole day's rows, so it is not re-read on every
 * remount — but it is not cached past a minute either, because the shift that
 * is open right now is inside the window it describes.
 */
const REPORT_STALE_TIME = 30_000;

/* ---------------------------------------------------------------- S2 */

export function overviewQueryOptions() {
  return {
    queryKey: queryKeys.overview.all(),
    queryFn: () => api.get<Overview>('/overview'),
    staleTime: REPORT_STALE_TIME,
  };
}

/** `GET /overview` — Admin only. */
export function useOverview(options: { enabled?: boolean } = {}) {
  return useQuery({ ...overviewQueryOptions(), enabled: options.enabled ?? true });
}

/* ---------------------------------------------------------------- S9 */

export function reportQueryOptions(range: RangeId = DEFAULT_RANGE) {
  return {
    queryKey: queryKeys.reports.range(range),
    // The window is computed from the server-offset clock and sent as a hint;
    // the answer carries the range the server actually used, and that is what
    // the screen prints (`rangeNote`).
    queryFn: () => api.get<Report>('/reports', { query: rangeParams(range) }),
    staleTime: REPORT_STALE_TIME,
  };
}

/** `GET /reports?from&to` — Manager+. */
export function useReport(range: RangeId = DEFAULT_RANGE, options: { enabled?: boolean } = {}) {
  return useQuery({ ...reportQueryOptions(range), enabled: options.enabled ?? true });
}

/* ------------------------------------------------------------- alerts */

export function alertsQueryOptions() {
  return {
    queryKey: queryKeys.alerts.all(),
    // The whole feed, read once — the badge counts the unread rows in it, so
    // asking `?unread=true` as well would be two reads of the same list.
    queryFn: () => api.get<Alert[]>('/alerts'),
    staleTime: REPORT_STALE_TIME,
  };
}

/** `GET /alerts` — any signed-in role; SSE keeps it live. */
export function useAlerts(options: { enabled?: boolean } = {}) {
  return useQuery({ ...alertsQueryOptions(), enabled: options.enabled ?? true });
}

/**
 * "Clear the bell" — `POST /alerts/read-all` answers with the feed it left
 * behind, so the response is written back rather than triggering a re-read.
 *
 * Not optimistic. It is cheap to be honest here: the rail is a list of things
 * that already happened, and pretending they are read before the server says so
 * buys a frame and risks a badge that disagrees with the next SSE event.
 */
export function useMarkAllAlertsRead() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => api.post<Alert[]>('/alerts/read-all'),
    onSuccess: (alerts) => {
      client.setQueryData(queryKeys.alerts.all(), alerts);
    },
  });
}

/** `POST /alerts/{id}/read` — one card. Idempotent by nature on the server. */
export function useMarkAlertRead() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => api.post<Alert>(`/alerts/${id}/read`),
    onSuccess: (alert) => {
      client.setQueryData<Alert[]>(queryKeys.alerts.all(), (current) =>
        (current ?? []).map((row) => (row.id === alert.id ? alert : row)),
      );
    },
  });
}
