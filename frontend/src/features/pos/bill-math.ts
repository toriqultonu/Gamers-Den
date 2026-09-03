/**
 * The POS bill's arithmetic — a **preview**, in the same sense as the booking
 * bill box (frontend/ARCHITECTURE.md §5.11).
 *
 * The server prices and settles and is always right. What is computed here is
 * what the operator has to see the instant they tap a card: the running
 * subtotal, how many points the redeem stepper is allowed to offer, and the
 * amount due after redemption. `POST /payments` re-derives all of it and
 * answers `SPLIT_MISMATCH` if the tender disagrees — so `due` here is not
 * decoration, it is the number the split panel has to add up to.
 *
 * Two figures come from the server and are never recomputed here: `gamingDue`
 * (unbilled blocks at their snapshot rates, which the client does not know)
 * and `prepaidCredit` (what a seated booking or play ticket already paid).
 *
 * **`prepaidCredit` is a credit note, not a discount.** The server's own
 * contract is explicit about it — "`sum(lines) == gamingDue + fnbDue +
 * tournamentDue == netTotal`. `prepaidCredit` is deliberately *outside* that
 * sum — it is a credit note, already excluded, never something to subtract a
 * second time" (`billing/domain/Bill.java`). Blocks a booking paid for carry a
 * `paid_tx_id` and never reach `gamingDue` at all, so taking the credit off
 * `due` again would tender less than `POST /payments` expects and 409 every
 * seated booking. It is shown, not subtracted.
 *
 * Everything is integer BDT — there is no paisa in this system (lib/money.ts).
 */

import type { BillDraft, Cart, EntryDraft, TicketDraft } from './bill-store';

/** What the cart's food and drink comes to — the server's own `total`. */
export function cartTotal(cart: Cart | null | undefined): number {
  if (!cart) return 0;
  if (typeof cart.total === 'number') return cart.total;
  return (cart.lines ?? []).reduce((sum, line) => sum + (line.lineTotal ?? 0), 0);
}

export function entriesTotal(entries: readonly EntryDraft[]): number {
  return entries.reduce((sum, entry) => sum + entry.fee * entry.qty, 0);
}

export function ticketsTotal(tickets: readonly TicketDraft[]): number {
  return tickets.reduce((sum, ticket) => sum + ticket.price * ticket.qty, 0);
}

export type BillFigures = {
  /** Unbilled blocks, from `GET /sessions/{id}/bill`. Counter sales: 0. */
  gamingDue: number;
  /** Entries already registered against this session, from the same read. */
  tournamentDue: number;
  /** Prepaid blocks a seated booking/token arrived with. Never a charge. */
  prepaidCredit: number;
  /** The attached member's points balance — the ceiling on redemption. */
  memberPoints: number;
};

export const NO_FIGURES: BillFigures = {
  gamingDue: 0,
  tournamentDue: 0,
  prepaidCredit: 0,
  memberPoints: 0,
};

export type BillTotals = {
  fnb: number;
  entries: number;
  tickets: number;
  /** Everything chargeable, before points. Equals the server's `netTotal`. */
  subtotal: number;
  /** The most the stepper may offer: min(points, subtotal). */
  maxRedeem: number;
  /** What the operator actually chose, clamped to `maxRedeem`. */
  redeem: number;
  /**
   * What a seated booking or play ticket already paid for this seat. Shown so
   * the customer can see their time is covered — **never subtracted**: the
   * server already left those blocks out of `gamingDue`.
   */
  credit: number;
  /** What has to be tendered — the number `splits[]` must sum to. Never negative. */
  due: number;
  /** floor(due / 20), and only with a member on the bill to earn them. */
  pointsEarned: number;
};

/**
 * The whole bill in one pass.
 *
 * `due = subtotal − redeem`, which is exactly the server's
 * `charges.gross() − pointsRedeemed` (`billing/domain/Settlement.java`), so a
 * balanced split panel is a split the settle accepts.
 */
export function billTotals(draft: BillDraft, figures: BillFigures = NO_FIGURES): BillTotals {
  const fnb = cartTotal(draft.cart);
  const entries = entriesTotal(draft.entries);
  const tickets = ticketsTotal(draft.tickets);

  const subtotal = Math.max(
    0,
    figures.gamingDue + figures.tournamentDue + fnb + entries + tickets,
  );
  const maxRedeem = maxRedeemable(draft.memberId === null ? 0 : figures.memberPoints, subtotal);
  const redeem = Math.min(Math.max(0, draft.redeemPoints), maxRedeem);
  const due = Math.max(0, subtotal - redeem);

  return {
    fnb,
    entries,
    tickets,
    subtotal,
    maxRedeem,
    redeem,
    credit: Math.max(0, Math.trunc(figures.prepaidCredit)),
    due,
    pointsEarned: draft.memberId === null ? 0 : pointsEarned(due),
  };
}

/**
 * The loyalty line P1 prints: "points earned · balance".
 *
 * `floor(due / 20)` of the **post-discount** total (`Money.pointsEarned`,
 * mirrored in `Settlement`: "Earning is on what was actually paid … so
 * redeeming points cannot quietly re-earn them"). A preview like every other
 * figure on this rail — the ledger the server writes is the record.
 */
export const TAKA_PER_POINT = 20;

export function pointsEarned(due: number): number {
  return due <= 0 ? 0 : Math.floor(due / TAKA_PER_POINT);
}

/**
 * "redemption at settle is a bill discount capped at min(points, total)"
 * (api-contract.md, Members). A member with 900 points on a ৳240 bill can burn
 * 240 of them, not 900 — the rest is not change.
 */
export function maxRedeemable(points: number, subtotal: number): number {
  return Math.max(0, Math.min(Math.trunc(points), Math.trunc(subtotal)));
}

export type RedeemStep = { value: number; label: string };

/**
 * The stepper's rungs — design.md §2: "None / 100 / 200 / Max".
 *
 * The fixed rungs disappear once they pass the cap, so the control can never
 * offer a redemption the server would refuse: on a ৳150 bill the choices are
 * None, 100 and Max 150; on a ৳80 bill they are None and Max 80.
 */
export function redeemSteps(maxRedeem: number): RedeemStep[] {
  const cap = Math.max(0, Math.trunc(maxRedeem));
  const values = [0, 100, 200, cap].filter(
    (value, index, all) => value <= cap && all.indexOf(value) === index,
  );
  return values.map((value) => ({
    value,
    label: value === 0 ? 'None' : value === cap ? `Max ${cap}` : String(value),
  }));
}

/**
 * The player-name field appears the moment an entry or a ticket is in the cart
 * — that name is printed on the P5 stub and seeded into the bracket, or on the
 * P6 play ticket (docs/tournaments.md §5, docs/bookings.md §3).
 */
export function needsPlayerName(draft: Pick<BillDraft, 'entries' | 'tickets'>): boolean {
  return draft.entries.length > 0 || draft.tickets.length > 0;
}

/**
 * The name that will actually be printed: the attached member's, else what was
 * typed, else "Walk-in guest" (docs/tournaments.md §5).
 *
 * A typed name never overrides an attached member — attaching is the stronger
 * statement, and the auto-fill is what the operator sees in the field.
 */
export const WALK_IN = 'Walk-in guest';

export function effectivePlayerName(draft: BillDraft): string {
  if (draft.memberName) return draft.memberName;
  const typed = draft.playerName.trim();
  return typed === '' ? WALK_IN : typed;
}

/** What the field shows: the member's name when attached, else free text. */
export function playerNameValue(draft: BillDraft): string {
  return draft.memberName ?? draft.playerName;
}

/** The price of one play ticket: blocks × the console's current block rate. */
export function ticketPrice(blocks: number, blockPrice: number): number {
  return Math.max(0, Math.trunc(blocks)) * Math.max(0, Math.trunc(blockPrice));
}
