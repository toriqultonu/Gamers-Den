'use client';

/**
 * The one Zustand store — frontend/ARCHITECTURE.md §4.2.
 *
 * Everything that can come from a query does (TanStack Query owns server
 * state); what is left is the handful of choices that belong to *this
 * terminal, right now* and are meaningless to anyone else: which console the
 * operator is looking at, whether the POS is billing that console or the
 * counter, and the bill draft.
 *
 * **The bill draft is the interesting part.** A POS bill is assembled from two
 * different kinds of thing:
 *
 *  - **Cart lines** — food and drink. These are server rows from the first
 *    line-add (§5.7): a mid-bill refresh must lose nothing, so the cart held
 *    here is always the server's own `Cart`, written back verbatim from every
 *    `PUT /carts/{id}/lines` response. The optimistic patch in
 *    `pos/mutations.ts` moves it early; the response reconciles it.
 *  - **Tournament entries and play tickets** — these have no cart row and no
 *    endpoint of their own in the settle path: they ride on `POST /payments`
 *    as `tournamentEntries[]` / `playTickets[]` (api-contract.md, Billing &
 *    payments). Until settle they exist nowhere but here, which is exactly why
 *    they are client state and why they must survive a category switch, a
 *    member attach and a redeem change without being re-derived.
 *
 * The draft belongs to one *bill target* — a station's session, or the
 * counter. Switching target clears it, because carrying a half-built counter
 * sale onto a console's bill is how the wrong customer gets charged.
 */

import { create } from 'zustand';
import type { Schemas } from '@/lib/api';
import type { BookingTab } from '@/features/bookings/schemas';
import type { ConsoleType } from '@/features/queue/schemas';
import type { PaymentSplitDraft } from '@/features/payments/schemas';

export type Cart = Schemas['Cart'];
export type CartLine = Schemas['CartLine'];
export type Item = Schemas['Item'];

/** design.md §1, S4: the bill is a station's, or the counter's. */
export const POS_MODES = ['station', 'counter'] as const;
export type PosMode = (typeof POS_MODES)[number];

/**
 * The menu's category rail. The first five come from `items.category`; the
 * last two are the POS-only categories docs/tournaments.md §5 and
 * docs/bookings.md §3 add — they sell things the stock table has never heard
 * of.
 */
export const MENU_CATEGORIES = [
  'ALL',
  'BEVERAGE',
  'FOOD',
  'SNACK',
  'EXTRAS',
  'PLAY_TICKET',
  'TOURNAMENT',
] as const;
export type MenuCategory = (typeof MENU_CATEGORIES)[number];

export const MENU_CATEGORY_LABELS: Record<MenuCategory, string> = {
  ALL: 'All',
  BEVERAGE: 'Beverage',
  FOOD: 'Food',
  SNACK: 'Snack',
  EXTRAS: 'Extras',
  PLAY_TICKET: 'Play ticket',
  TOURNAMENT: 'Tournament',
};

/** One tournament's entries in the draft. `qty` 2 settles as two entries. */
export type EntryDraft = {
  tournamentId: number;
  name: string;
  fee: number;
  qty: number;
};

/** One prepaid play ticket in the draft — console type × length, × qty. */
export type TicketDraft = {
  consoleType: ConsoleType;
  blocks: number;
  price: number;
  qty: number;
};

/**
 * Which bill is being built. `station:41` is the session on the selected
 * console; `counter` is a walk-up sale with no session behind it.
 */
export type BillTarget = string;

export type BillDraft = {
  /** The server's cart, verbatim. `null` until the first line is added. */
  cart: Cart | null;
  entries: EntryDraft[];
  tickets: TicketDraft[];
  /** The member attached to this bill, if any. */
  memberId: number | null;
  memberName: string | null;
  memberPoints: number;
  memberWallet: number;
  /**
   * True once the operator has attached or removed a member by hand. Before
   * that the station's own member auto-attaches; after it, the operator's
   * choice stands even when the session says otherwise (design.md §2,
   * MemberSearch "attached / auto-attached").
   */
  memberTouched: boolean;
  /** Points the operator has chosen to burn. Capped at settle *and* here. */
  redeemPoints: number;
  /** The name printed on an entry stub or a play ticket. */
  playerName: string;
  /**
   * The tender rows. Empty means untouched — the panel then shows one cash row
   * for the whole bill, which is the overwhelmingly common sale and keeps the
   * split balanced as the bill grows (`payments/schemas.ts`,
   * `effectiveSplits`). Amounts are the operator's own text, kept verbatim so
   * a failed settle gives the entered figures back rather than a rounded
   * reading of them (§4.4: an error never destroys entered data).
   */
  splits: PaymentSplitDraft[];
};

export const EMPTY_DRAFT: BillDraft = {
  cart: null,
  entries: [],
  tickets: [],
  memberId: null,
  memberName: null,
  memberPoints: 0,
  memberWallet: 0,
  memberTouched: false,
  redeemPoints: 0,
  playerName: '',
  splits: [],
};

export type AttachedMember = {
  id: number;
  name: string;
  points: number;
  wallet: number;
};

export type PosSlice = {
  posMode: PosMode;
  selectedStationId: number | null;
  category: MenuCategory;
  /** The play-ticket length picker, in 30-minute blocks. */
  ticketBlocks: number;
  /** 1024–1279: the ticket column hides behind a Preview button (design.md §4). */
  previewOpen: boolean;
  /** The target the current draft belongs to — switching it clears the draft. */
  target: BillTarget;
  draft: BillDraft;

  setPosMode: (mode: PosMode) => void;
  selectStation: (stationId: number | null) => void;
  setCategory: (category: MenuCategory) => void;
  setTicketBlocks: (blocks: number) => void;
  setPreviewOpen: (open: boolean) => void;

  /** Points the draft at a bill; clears it when that is a different bill. */
  setTarget: (target: BillTarget) => void;
  setCart: (cart: Cart | null) => void;
  addEntry: (entry: Omit<EntryDraft, 'qty'>, delta?: number) => void;
  addTicket: (ticket: Omit<TicketDraft, 'qty'>, delta?: number) => void;
  attachMember: (member: AttachedMember) => void;
  autoAttachMember: (member: AttachedMember) => void;
  clearMember: () => void;
  setRedeemPoints: (points: number) => void;
  setPlayerName: (name: string) => void;
  setSplits: (splits: PaymentSplitDraft[]) => void;
  resetDraft: () => void;
};

/**
 * S14's rail — §4.2's `bookingsTab`, `selectedBookingId`, `bookingFormOpen`.
 *
 * The three of them are one choice made of three fields: the rail shows the
 * form, or a booking, or its idle hint, and never two of those at once. So the
 * setters are written as that choice rather than as three independent flags —
 * picking a row closes the form, opening the form drops the selection, and
 * switching tab clears both, because a booking selected on Upcoming is not on
 * the screen the operator is now looking at.
 *
 * All of it is safely lost on a refresh: what a booking *is* comes from
 * `['bookings', …]`, and this only remembers where the operator was looking.
 */
export type BookingsSlice = {
  bookingsTab: BookingTab;
  selectedBookingId: number | null;
  bookingFormOpen: boolean;

  setBookingsTab: (tab: BookingTab) => void;
  selectBooking: (bookingId: number | null) => void;
  openBookingForm: () => void;
  closeBookingForm: () => void;
};

/**
 * The store. Later screens add their own slices to this same object
 * (`alertsRailOpen`, `selectedTournamentId`, `bookingsTab`, …) — §4.2 is one
 * store, not one per feature.
 */
export const useAppStore = create<PosSlice & BookingsSlice>()((set, get) => ({
  posMode: 'counter',
  selectedStationId: null,
  category: 'ALL',
  ticketBlocks: 2,
  previewOpen: false,
  target: 'counter',
  draft: EMPTY_DRAFT,

  setPosMode: (mode) => set({ posMode: mode }),
  selectStation: (stationId) => set({ selectedStationId: stationId }),
  setCategory: (category) => set({ category }),
  setTicketBlocks: (blocks) => set({ ticketBlocks: Math.max(1, Math.trunc(blocks)) }),
  setPreviewOpen: (open) => set({ previewOpen: open }),

  setTarget: (target) =>
    set(get().target === target ? {} : { target, draft: EMPTY_DRAFT }),

  setCart: (cart) => set({ draft: { ...get().draft, cart } }),

  addEntry: (entry, delta = 1) =>
    set({ draft: { ...get().draft, entries: bump(get().draft.entries, entry, delta, isEntry) } }),

  addTicket: (ticket, delta = 1) =>
    set({ draft: { ...get().draft, tickets: bump(get().draft.tickets, ticket, delta, isTicket) } }),

  // A hand-attach is sticky, and it re-opens the redeem choice from zero:
  // the new member's points are not the old one's.
  attachMember: (member) =>
    set({
      draft: {
        ...get().draft,
        memberId: member.id,
        memberName: member.name,
        memberPoints: member.points,
        memberWallet: member.wallet,
        memberTouched: true,
        redeemPoints: 0,
        splits: [],
      },
    }),

  /** The station's own member, filled in for the operator — not sticky. */
  autoAttachMember: (member) => {
    const draft = get().draft;
    if (draft.memberTouched || draft.memberId === member.id) return;
    set({
      draft: {
        ...draft,
        memberId: member.id,
        memberName: member.name,
        memberPoints: member.points,
        memberWallet: member.wallet,
        redeemPoints: 0,
        splits: [],
      },
    });
  },

  clearMember: () =>
    set({
      draft: {
        ...get().draft,
        memberId: null,
        memberName: null,
        memberPoints: 0,
        memberWallet: 0,
        memberTouched: true,
        redeemPoints: 0,
        splits: [],
      },
    }),

  setRedeemPoints: (points) =>
    set({ draft: { ...get().draft, redeemPoints: Math.max(0, Math.trunc(points)) } }),

  setPlayerName: (name) => set({ draft: { ...get().draft, playerName: name } }),

  // The panel hands over the whole array: the rules that decide it (which
  // method absorbs the remainder, what a removed row gives back) are pure
  // functions in `payments/schemas.ts`, so they are testable without a store.
  setSplits: (splits) => set({ draft: { ...get().draft, splits } }),

  resetDraft: () => set({ draft: EMPTY_DRAFT }),

  /* ------------------------------------------------------------- bookings */

  bookingsTab: 'upcoming',
  selectedBookingId: null,
  bookingFormOpen: false,

  setBookingsTab: (tab) =>
    set(
      get().bookingsTab === tab
        ? {}
        : { bookingsTab: tab, selectedBookingId: null, bookingFormOpen: false },
    ),

  selectBooking: (bookingId) => set({ selectedBookingId: bookingId, bookingFormOpen: false }),

  openBookingForm: () => set({ bookingFormOpen: true, selectedBookingId: null }),

  closeBookingForm: () => set({ bookingFormOpen: false }),
}));

/** Test isolation and sign-out: the terminal forgets what it was selling. */
export function resetPosStore(): void {
  useAppStore.setState({
    posMode: 'counter',
    selectedStationId: null,
    category: 'ALL',
    ticketBlocks: 2,
    previewOpen: false,
    target: 'counter',
    draft: EMPTY_DRAFT,
    bookingsTab: 'upcoming',
    selectedBookingId: null,
    bookingFormOpen: false,
  });
}

/** The target string for a bill, so two screens name the same bill alike. */
export function billTarget(mode: PosMode, sessionId: number | null | undefined): BillTarget {
  return mode === 'counter' ? 'counter' : `station:${sessionId ?? 'none'}`;
}

/* -------------------------------------------------------------- internals */

const isEntry = (a: EntryDraft, b: Omit<EntryDraft, 'qty'>) => a.tournamentId === b.tournamentId;

const isTicket = (a: TicketDraft, b: Omit<TicketDraft, 'qty'>) =>
  a.consoleType === b.consoleType && a.blocks === b.blocks;

/**
 * Add `delta` of a draft line, dropping it at zero — the "qty-min removes"
 * rule the cart lines follow too (design.md §2, CartLine).
 */
function bump<T extends { qty: number }>(
  lines: T[],
  line: Omit<T, 'qty'>,
  delta: number,
  same: (existing: T, candidate: Omit<T, 'qty'>) => boolean,
): T[] {
  const index = lines.findIndex((existing) => same(existing, line));
  if (index === -1) {
    return delta > 0 ? [...lines, { ...(line as T), qty: delta }] : lines;
  }
  const next = Math.max(0, (lines[index] as T).qty + delta);
  if (next === 0) return lines.filter((_, i) => i !== index);
  return lines.map((existing, i) => (i === index ? { ...existing, qty: next } : existing));
}
