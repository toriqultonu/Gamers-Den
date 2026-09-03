'use client';

/**
 * The POS reads: `['items']`, `['pricing']`, `['members', q]`.
 *
 * The menu grid is three different things stacked into one grid, and only the
 * first is a menu:
 *
 *  - **stock items** from `GET /items`, minus the retired rows;
 *  - **tournament entries** — one card per OPEN event, priced at its fee and
 *    disabled when `slotsLeft` is 0 (docs/tournaments.md §5). The list itself
 *    is `['tournaments']`, already owned by `features/tournaments/queries.ts`;
 *  - **play tickets** — priced from the rate card, not the stock table, and
 *    deliberately independent of whether any console is free
 *    (docs/bookings.md §3).
 */

import { useQuery } from '@tanstack/react-query';
import { api, type Schemas } from '@/lib/api';
import { queryKeys } from '@/lib/query-keys';
import type { ConsoleType } from '@/features/queue/schemas';
import { memberSearchQueryOptions as memberDirectoryQueryOptions } from '@/features/members/queries';
import { ticketPrice } from './bill-math';

export type Item = Schemas['Item'];
export type Pricing = Schemas['Pricing'];
export type Member = Schemas['Member'];
export type PageResponseMember = Schemas['PageResponseMember'];

/* -------------------------------------------------------------- the menu */

/**
 * `GET /items` — the whole menu, retired rows included.
 *
 * F07 read it as `?active=true` because the POS grid only ever wanted sellable
 * rows; F13 widened it, exactly as that task's note said it would ("S10 will
 * widen the read, not fork it"). One key, one shape, three readers: the POS
 * grid filters the retired rows out on the way to the screen
 * (`visibleItems`), S5 lists them because a stock record is not a menu, and
 * S10's editor edits them because reactivating one is the point.
 */
export function menuQueryOptions() {
  return {
    queryKey: queryKeys.items.all(),
    queryFn: () => api.get<Item[]>('/items'),
  };
}

export function useMenu(options: { enabled?: boolean } = {}) {
  return useQuery({ ...menuQueryOptions(), enabled: options.enabled ?? true });
}

/* ------------------------------------------------------------ the rates */

/**
 * `GET /pricing` — one row per console type, with `currentBlockPrice` already
 * carrying the morning discount if we are inside the window. The play-ticket
 * cards price off that, so a ticket sold at 11:00 costs what a block costs at
 * 11:00. The server re-prices at settle and wins (§5.11).
 */
export function pricingQueryOptions() {
  return {
    queryKey: queryKeys.pricing.all(),
    queryFn: () => api.get<Pricing[]>('/pricing'),
    staleTime: 60_000,
  };
}

export function usePricing(options: { enabled?: boolean } = {}) {
  return useQuery({ ...pricingQueryOptions(), enabled: options.enabled ?? true });
}

/** The block price for a console type right now, 0 when the card has not loaded. */
export function blockPriceOf(
  pricing: Pricing[] | undefined,
  consoleType: ConsoleType | string | undefined,
): number {
  const row = (pricing ?? []).find((rate) => rate.consoleType === consoleType);
  return row?.currentBlockPrice ?? row?.perHalfHour ?? 0;
}

export type PlayTicketProduct = {
  consoleType: ConsoleType;
  blocks: number;
  price: number;
  /** Whether the rate card has actually answered — an unpriced card is dead. */
  priced: boolean;
};

/**
 * The play-ticket cards: one per console type at the length the picker is on.
 *
 * There is no availability check here on purpose. A play ticket is prepaid
 * time in a queue — "sellable while every console is busy … which is the whole
 * point of the queue" (api-contract.md, `POST /payments`).
 */
export function playTicketProducts(
  pricing: Pricing[] | undefined,
  blocks: number,
  consoleTypes: readonly ConsoleType[] = ['PS5', 'PS4'],
): PlayTicketProduct[] {
  return consoleTypes.map((consoleType) => {
    const rate = blockPriceOf(pricing, consoleType);
    return {
      consoleType,
      blocks,
      price: ticketPrice(blocks, rate),
      priced: rate > 0,
    };
  });
}

/* ----------------------------------------------------------- the members */

/**
 * `GET /members?q=` — "One box over name and phone" (api-contract.md).
 *
 * The read itself is S6's (`features/members/queries.ts`): one key, one shape.
 * The POS only narrows it — only asked once the operator has typed something,
 * because an empty box on a bill means "no member", not "show me the whole
 * directory", where S6's table opens on exactly that listing under the same
 * `['members', '']` key.
 */
export function memberSearchQueryOptions(query: string) {
  const term = query.trim();
  return {
    ...memberDirectoryQueryOptions(term),
    enabled: term.length > 0,
  };
}

export function useMemberSearch(query: string) {
  return useQuery(memberSearchQueryOptions(query));
}

/** The rows the search box lists — capped, because the rail is 348px wide. */
export function memberResults(page: PageResponseMember | undefined, limit = 4): Member[] {
  return (page?.content ?? []).slice(0, limit);
}
