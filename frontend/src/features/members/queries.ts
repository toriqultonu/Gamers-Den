'use client';

/**
 * Member reads: `['members', q]` and `['members', id]` — the canonical keys
 * (frontend/ARCHITECTURE.md §4.1).
 *
 * The two are the same namespace on purpose: the directory is a search whose
 * term happens to be empty most of the time. `GET /members` with no `q` "lists
 * everyone, by name" (backend `MemberController.search`), which is exactly what
 * S6 opens on — so the table and the POS rail's attach box are one query with
 * one shape, and one cache entry per settled search term.
 *
 * The term is debounced by the screen before it reaches the key
 * (`lib/use-debounced-value.ts`): a phone number typed at the counter is one
 * request, not eleven. `keepPreviousData` stops the table blinking empty
 * between two settled searches.
 */

import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { queryKeys } from '@/lib/query-keys';
import type { Member, MemberDetail, PageResponseMember } from './schemas';

/**
 * The server's own default page size (`@RequestParam(defaultValue = "50")`).
 * A venue directory is a few hundred rows at most; S6 has no pager, so this is
 * the whole table and the POS rail slices what it can show from the same
 * answer.
 */
export const MEMBER_PAGE_SIZE = 50;

export function memberSearchQueryOptions(query: string) {
  const term = query.trim();
  return {
    queryKey: queryKeys.members.search(term),
    queryFn: () =>
      api.get<PageResponseMember>('/members', { query: { q: term, size: MEMBER_PAGE_SIZE } }),
    placeholderData: keepPreviousData,
  };
}

export function useMemberDirectory(query: string) {
  return useQuery(memberSearchQueryOptions(query));
}

/** The rows a page carries, in the server's order (name, case-insensitive). */
export function memberRows(page: PageResponseMember | undefined): Member[] {
  return page?.content ?? [];
}

/**
 * `GET /members/{id}` — the rail: wallet, points, recent visits and the
 * bookings they hold or have held. Read on selection, not with the table: the
 * table row carries the money, the rail carries the history.
 */
export function memberDetailQueryOptions(id: number) {
  return {
    queryKey: queryKeys.members.detail(id),
    queryFn: () => api.get<MemberDetail>(`/members/${id}`),
  };
}

export function useMemberDetail(id: number | null | undefined) {
  return useQuery({
    ...memberDetailQueryOptions(id ?? 0),
    enabled: typeof id === 'number' && id > 0,
  });
}
