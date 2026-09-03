'use client';

/**
 * S10's writes: stations, the rate card, staff, and the menu rows.
 *
 * None of them is optimistic, and none of them needs an `Idempotency-Key`.
 * Both facts come from the same place: configuration writes are not money.
 * `POST /stations` is not on the guarded route list (api-contract.md §1), a
 * retried `PATCH /items/{id}` sets the same absolute stock figure it set the
 * first time, and a duplicate name is already a 409 — so there is nothing a key
 * would protect. What there *is* to protect is the operator's confidence: a
 * station drawn before the server accepted it, or a staff row that vanished
 * optimistically and came back on a `STAFF_ON_SHIFT` refusal, would be a
 * roster that lies. Every hook here waits for the response and then
 * invalidates the canonical key the rest of the app reads.
 *
 * The refusals these carry are the ones design.md §1 gives S10 a state for:
 * `DUPLICATE_NAME` on a taken name, `STATION_IN_USE` on a console someone is
 * playing, and `STAFF_ON_SHIFT` on a staff member with a drawer open.
 */

import { useMutation, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { queryKeys } from '@/lib/query-keys';
import type {
  CreateItemInput,
  CreateStaffInput,
  CreateStationInput,
  Item,
  Pricing,
  PricingFormInput,
  Staff,
  Station,
  UpdateItemInput,
} from './schemas';

/**
 * A station added or removed changes the floor, and the floor is `['stations']`
 * — the same key S3 draws from, so the change lands there without a second read
 * of a second shape.
 */
function invalidateStations(client: QueryClient): void {
  void client.invalidateQueries({ queryKey: queryKeys.stations.all() });
}

/* -------------------------------------------------------------- stations */

/** `POST /stations` (Admin) — 409 `DUPLICATE_NAME` when the name is taken. */
export function useCreateStation() {
  const client = useQueryClient();
  return useMutation<Station, unknown, CreateStationInput>({
    mutationFn: (input) => api.post<Station>('/stations', input),
    onSuccess: () => invalidateStations(client),
  });
}

/**
 * `DELETE /stations/{id}` (Admin) — 409 `STATION_IN_USE` while a session, a
 * reserved match or a checked-in arrival still points at it.
 */
export function useDeleteStation() {
  const client = useQueryClient();
  return useMutation<void, unknown, { id: number }>({
    mutationFn: ({ id }) => api.delete<void>(`/stations/${id}`),
    onSuccess: () => invalidateStations(client),
  });
}

/* --------------------------------------------------------------- pricing */

/**
 * `PUT /pricing` (Admin) — an array, one entry per console type being changed.
 *
 * "New blocks only — running sessions keep the prices they purchased"
 * (api-contract.md). Nothing on the floor moves, so only `['pricing']` is
 * invalidated: the POS play-ticket cards and S14's bill box re-price on the
 * next read, and the session panel keeps the snapshot it bought.
 */
export function useUpdatePricing() {
  const client = useQueryClient();
  return useMutation<Pricing[], unknown, PricingFormInput[]>({
    mutationFn: (rates) => api.put<Pricing[]>('/pricing', rates),
    onSuccess: (rates) => {
      client.setQueryData(queryKeys.pricing.all(), rates);
    },
  });
}

/* ----------------------------------------------------------------- staff */

/** `POST /staff` (Admin) — Manager or Cashier with a 4-digit PIN. */
export function useCreateStaff() {
  const client = useQueryClient();
  return useMutation<Staff, unknown, CreateStaffInput>({
    mutationFn: (input) => api.post<Staff>('/staff', input),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: queryKeys.staff.all() });
    },
  });
}

/**
 * `DELETE /staff/{id}` (Admin) — 409 `STAFF_ON_SHIFT` while their drawer is
 * open. The row is deactivated rather than deleted, "so shifts, sessions and
 * transactions keep pointing at it" — which is why the refusal matters: the
 * shift has to be closed and counted before the person leaves the roster.
 */
export function useDeleteStaff() {
  const client = useQueryClient();
  return useMutation<void, unknown, { id: number }>({
    mutationFn: ({ id }) => api.delete<void>(`/staff/${id}`),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: queryKeys.staff.all() });
    },
  });
}

/* ----------------------------------------------------------- menu & stock */

function invalidateItems(client: QueryClient): void {
  void client.invalidateQueries({ queryKey: queryKeys.items.all() });
}

/** `POST /items` (Manager+) — 409 `DUPLICATE_NAME`; opening stock is audited. */
export function useCreateItem() {
  const client = useQueryClient();
  return useMutation<Item, unknown, CreateItemInput>({
    mutationFn: (input) => api.post<Item>('/items', input),
    onSuccess: () => invalidateItems(client),
  });
}

/**
 * `PATCH /items/{id}` (Manager+) — "stock is the absolute counted figure; the
 * difference is audited as one signed MANUAL_ADJUST movement".
 *
 * That is the reason this row editor sends a count rather than a delta: the
 * operator has just counted the shelf, and the number in their hand is the
 * truth. The server works out what moved.
 */
export function useUpdateItem() {
  const client = useQueryClient();
  return useMutation<Item, unknown, { id: number } & UpdateItemInput>({
    mutationFn: ({ id, ...patch }) => api.patch<Item>(`/items/${id}`, patch),
    onSuccess: () => invalidateItems(client),
  });
}

/**
 * `DELETE /items/{id}` (Manager+) — deleted outright while nothing points at
 * it, deactivated once it has sales history, so the audit survives either way.
 */
export function useDeleteItem() {
  const client = useQueryClient();
  return useMutation<void, unknown, { id: number }>({
    mutationFn: ({ id }) => api.delete<void>(`/items/${id}`),
    onSuccess: () => invalidateItems(client),
  });
}
