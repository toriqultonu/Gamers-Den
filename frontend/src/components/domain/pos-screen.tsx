'use client';

/**
 * S4 — Point of sale.
 *
 * Three columns at ≥1280: the menu grid, the bill, and the 80mm ticket. The
 * ticket column collapses behind a Preview button between 1024 and 1279
 * (design.md §4); the ticket itself is the server's stored render, read back
 * from the print job the settle created (invariant §5.6).
 *
 * The menu is one grid over three different kinds of product, and the
 * difference that matters is what turns a card off:
 *
 *  - a stock item is off when the shelf is empty;
 *  - a tournament entry is off when the event is full (docs/tournaments.md §5);
 *  - a play ticket is **never** off for want of a console — selling prepaid
 *    time while every console is busy is the queue's whole purpose
 *    (docs/bookings.md §3).
 *
 * The bill is assembled from server state (the session's unbilled blocks, the
 * cart) and draft state (entries, tickets), and only the cart moves
 * optimistically. Everything money-bearing that is not a cart line waits for
 * `POST /payments` to say it happened.
 *
 * **The settle is the sharp end of that.** One call, never optimistic, and on
 * refusal the bill is exactly as it was: the lines, the member, the redemption
 * and the tender amounts all still on screen with the notice above them, ready
 * to send again under the same `Idempotency-Key`. Success is the only thing
 * that clears the draft — and it clears it *after* the response, holding on to
 * the receipt so the ticket column can draw it.
 */

import { useEffect, useMemo, useState } from 'react';
import { AccessNotice } from './access-notice';
import { BillPanel } from './bill-panel';
import { MenuItemCard } from './menu-item-card';
import { PaymentSplit } from './payment-split';
import { ReceiptPreview } from './receipt-preview';
import { Button } from '@/components/ui/button';
import { ChipSelect } from '@/components/ui/chip-select';
import { TimeStepper, formatBlocks } from '@/components/ui/time-stepper';
import { errorNotice, isApiError } from '@/lib/api';
import { venueToday } from '@/lib/time';
import { useSessionBill, useStations, type Station } from '@/features/sessions/queries';
import { useTournaments, type Tournament } from '@/features/tournaments/queries';
import {
  memberResults,
  playTicketProducts,
  useMemberSearch,
  useMenu,
  usePricing,
  type Item,
  type Member,
} from '@/features/pos/queries';
import { useSetCartLine } from '@/features/pos/mutations';
import {
  MENU_CATEGORIES,
  MENU_CATEGORY_LABELS,
  billTarget,
  useAppStore,
  type MenuCategory,
} from '@/features/pos/bill-store';
import { billTotals, effectivePlayerName, type BillFigures } from '@/features/pos/bill-math';
import { useSettle } from '@/features/payments/mutations';
import {
  validateSplits,
  type PaymentSplitDraft,
  type SettleResult,
} from '@/features/payments/schemas';
import type { ConsoleType } from '@/features/queue/schemas';

export function PosScreen() {
  const posMode = useAppStore((state) => state.posMode);
  const selectedStationId = useAppStore((state) => state.selectedStationId);
  const category = useAppStore((state) => state.category);
  const ticketBlocks = useAppStore((state) => state.ticketBlocks);
  const previewOpen = useAppStore((state) => state.previewOpen);
  const draft = useAppStore((state) => state.draft);
  const store = useAppStore.getState;

  const [notice, setNotice] = useState<string | null>(null);
  const [memberQuery, setMemberQuery] = useState('');

  const stations = useStations();
  const menu = useMenu();
  const tournaments = useTournaments();
  const pricing = usePricing();

  const billable = useMemo(() => billableStations(stations.data), [stations.data]);
  const station = billable.find((row) => row.id === selectedStationId) ?? billable[0] ?? null;
  const sessionId = posMode === 'station' ? (station?.session?.id ?? null) : null;
  const bill = useSessionBill(sessionId);

  const members = useMemberSearch(memberQuery);
  const setCartLine = useSetCartLine();

  // The draft belongs to one bill. Switching console — or between the counter
  // and a station — starts a new one rather than carrying lines across.
  const target = billTarget(posMode, sessionId);
  useEffect(() => {
    store().setTarget(target);
  }, [store, target]);

  // A station bill opens with the session's own member filled in; the operator
  // can overrule it, and then their choice sticks (`memberTouched`).
  const billMemberId = bill.data?.memberId;
  useEffect(() => {
    if (typeof billMemberId !== 'number') return;
    store().autoAttachMember({
      id: billMemberId,
      name: bill.data?.memberName ?? 'Member',
      points: bill.data?.memberPoints ?? 0,
      wallet: bill.data?.memberWallet ?? 0,
    });
  }, [store, billMemberId, bill.data?.memberName, bill.data?.memberPoints, bill.data?.memberWallet]);

  // Loyalty is a station-bill affair. `POST /payments` carries no `memberId`
  // and a counter cart has no seat to inherit one from, so the server settles a
  // counter sale with no member at all — quoting a redemption there would
  // tender short and 409 SPLIT_MISMATCH every time
  // (billing/domain/PaymentService.java, `counter`/`walkUp`).
  const stationMode = posMode === 'station';
  const loyaltyEnabled = stationMode;

  const figures: BillFigures = {
    gamingDue: stationMode ? (bill.data?.gamingDue ?? 0) : 0,
    tournamentDue: stationMode ? (bill.data?.tournamentDue ?? 0) : 0,
    prepaidCredit: stationMode ? (bill.data?.prepaidCredit ?? 0) : 0,
    memberPoints: loyaltyEnabled ? draft.memberPoints : 0,
  };
  const totals = billTotals(draft, figures);

  /* --------------------------------------------------------------- settle */

  const settle = useSettle();
  const splitIssues = validateSplits(
    draft.splits,
    totals.due,
    loyaltyEnabled ? draft.memberWallet : 0,
  );
  // The receipt of the settle that just happened. Held here rather than in the
  // draft because it outlives the bill it came from: the draft clears, the
  // ticket stays on screen until the next sale begins.
  const [receipt, setReceipt] = useState<SettleResult | null>(null);

  const openEvents = useMemo(() => openTournaments(tournaments.data), [tournaments.data]);
  const entryCaps = useMemo(
    () => Object.fromEntries(openEvents.map((event) => [event.id as number, event.slotsLeft ?? 0])),
    [openEvents],
  );
  // An unpriced card is a dead card: without the rate card there is no price
  // to charge, so the ticket simply is not on the menu yet.
  const tickets = useMemo(
    () => playTicketProducts(pricing.data, ticketBlocks).filter((product) => product.priced),
    [pricing.data, ticketBlocks],
  );
  const items = useMemo(() => visibleItems(menu.data, category), [menu.data, category]);

  const showEntries = category === 'ALL' || category === 'TOURNAMENT';
  const showTickets = category === 'ALL' || category === 'PLAY_TICKET';
  const cardCount =
    items.length + (showEntries ? openEvents.length : 0) + (showTickets ? tickets.length : 0);

  /* --------------------------------------------------------------- writes */

  const itemsById = useMemo(() => {
    const index = new Map<number, Item>();
    for (const item of menu.data ?? []) {
      if (typeof item.id === 'number') index.set(item.id, item);
    }
    return index;
  }, [menu.data]);

  const setLine = (item: Item, qty: number) => {
    setNotice(null);
    setCartLine.mutate({ item, qty, sessionId }, { onError: (error) => setNotice(errorNotice(error)) });
  };

  const onAddItem = (item: Item) => {
    const current = (draft.cart?.lines ?? []).find((line) => line.itemId === item.id)?.qty ?? 0;
    setLine(item, current + 1);
  };

  const onCartQty = (itemId: number, qty: number) => {
    const item = itemsById.get(itemId);
    if (!item) return;
    setLine(item, qty);
  };

  const onEntryQty = (tournamentId: number, qty: number) => {
    const entry = draft.entries.find((line) => line.tournamentId === tournamentId);
    if (!entry) return;
    store().addEntry(
      { tournamentId, name: entry.name, fee: entry.fee },
      Math.max(0, Math.trunc(qty)) - entry.qty,
    );
  };

  const onTicketQty = (consoleType: string, blocks: number, qty: number) => {
    const ticket = draft.tickets.find(
      (line) => line.consoleType === consoleType && line.blocks === blocks,
    );
    if (!ticket) return;
    store().addTicket(
      { consoleType: ticket.consoleType, blocks, price: ticket.price },
      Math.max(0, Math.trunc(qty)) - ticket.qty,
    );
  };

  const onAttachMember = (member: Member) => {
    if (typeof member.id !== 'number') return;
    store().attachMember({
      id: member.id,
      name: member.name ?? 'Member',
      points: member.points ?? 0,
      wallet: member.wallet ?? 0,
    });
    setMemberQuery('');
  };

  const onSplitsChange = (splits: PaymentSplitDraft[]) => {
    setNotice(null);
    store().setSplits(splits);
  };

  /**
   * Take the money. Nothing local moves until the server answers.
   *
   * The intent names the bill, not the attempt: a timeout and the operator's
   * second press are the same intent, so `lib/api.ts` sends the same
   * `Idempotency-Key` and the server replays its stored receipt instead of
   * charging twice.
   */
  const onSettle = () => {
    if (settle.isPending || !splitIssues.ok) return;
    setNotice(null);

    settle.mutate(
      {
        intent: `settle:${target}`,
        sessionId,
        cartId: sessionId === null ? (draft.cart?.id ?? null) : null,
        splits: draft.splits,
        due: totals.due,
        redeemPoints: totals.redeem,
        entries: draft.entries,
        tickets: draft.tickets,
        playerName: effectivePlayerName(draft),
        memberId: loyaltyEnabled ? draft.memberId : null,
      },
      {
        onSuccess: (result) => {
          // Only now. The tokens and the receipt exist, so the bill they came
          // from can go.
          setReceipt(result);
          store().resetDraft();
          store().setPreviewOpen(true);
          setMemberQuery('');
        },
        // The bill is untouched — no rollback, because nothing rolled forward.
        // design.md §1, S4: "settle failure keeps bill intact, retry".
        onError: (error) => setNotice(errorNotice(error, 'The payment was not taken.')),
      },
    );
  };

  /* ---------------------------------------------------------------- render */

  // A 403 on the menu refuses the screen itself — there is nothing behind it.
  if (isApiError(menu.error) && menu.error.status === 403) {
    return <AccessNotice screen="Point of sale" />;
  }

  return (
    <div data-testid="pos-screen" className="flex min-h-0 flex-1">
      <div className="flex min-w-0 flex-1 flex-col gap-4 overflow-auto p-5">
        {menu.isError ? (
          <p role="alert" data-testid="pos-error" className="border-2 border-accent px-3 py-2 text-body text-accent-strong">
            {errorNotice(menu.error, 'The menu could not be read — the bill is untouched.')}
          </p>
        ) : null}

        <ChipSelect
          label="Menu category"
          options={MENU_CATEGORIES.map((value) => ({
            value,
            label: MENU_CATEGORY_LABELS[value],
          }))}
          value={category}
          onChange={(value) => store().setCategory(value as MenuCategory)}
        />

        {showTickets ? (
          <section
            data-testid="ticket-length"
            className="flex items-center gap-4 border-2 border-divider p-3"
          >
            <div className="min-w-0">
              <h2 className="type-label opacity-55">Play-ticket length</h2>
              <p className="text-[11px] opacity-55">
                Prepaid time with a daily token — sellable while every console is busy.
              </p>
            </div>
            <TimeStepper
              blocks={ticketBlocks}
              max={48}
              onChange={(blocks) => store().setTicketBlocks(blocks)}
              className="ml-auto w-[300px]"
            />
          </section>
        ) : null}

        {menu.isPending ? (
          <MenuSkeleton />
        ) : cardCount === 0 ? (
          <p data-testid="menu-empty" className="text-body opacity-70">
            Menu is empty
          </p>
        ) : (
          <div className="grid grid-cols-4 gap-2.5">
            {showEntries
              ? openEvents.map((event) => (
                  <MenuItemCard
                    key={`tournament-${event.id}`}
                    variant="entry"
                    kicker="Tournament"
                    name={`Entry — ${event.name ?? 'Tournament'}`}
                    price={event.entryFee ?? 0}
                    note={(event.slotsLeft ?? 0) > 0 ? `${event.slotsLeft} slots left` : 'Full'}
                    noteUrgent={(event.slotsLeft ?? 0) <= 2}
                    disabled={(event.slotsLeft ?? 0) <= 0}
                    onAdd={() =>
                      store().addEntry({
                        tournamentId: event.id as number,
                        name: event.name ?? 'Tournament',
                        fee: event.entryFee ?? 0,
                      })
                    }
                  />
                ))
              : null}

            {showTickets
              ? tickets.map((ticket) => (
                  <MenuItemCard
                    key={`ticket-${ticket.consoleType}`}
                    variant="ticket"
                    kicker="Play ticket"
                    name={`Play ticket — ${ticket.consoleType} · ${formatBlocks(ticket.blocks)}`}
                    price={ticket.price}
                    note="prepaid · gets a token"
                    onAdd={() =>
                      store().addTicket({
                        consoleType: ticket.consoleType as ConsoleType,
                        blocks: ticket.blocks,
                        price: ticket.price,
                      })
                    }
                  />
                ))
              : null}

            {items.map((item) => (
              <MenuItemCard
                key={item.id}
                variant="item"
                kicker={MENU_CATEGORY_LABELS[(item.category ?? 'EXTRAS') as MenuCategory]}
                name={item.name ?? `Item #${item.id}`}
                price={item.price ?? 0}
                note={
                  item.outOfStock
                    ? 'Out of stock'
                    : item.lowStock
                      ? `${item.stock ?? 0} left · low`
                      : `${item.stock ?? 0} left`
                }
                noteUrgent={Boolean(item.lowStock || item.outOfStock)}
                disabled={Boolean(item.outOfStock)}
                onAdd={() => onAddItem(item)}
              />
            ))}
          </div>
        )}
      </div>

      <BillPanel
        mode={posMode}
        onModeChange={(mode) => {
          setNotice(null);
          store().setPosMode(mode);
        }}
        billableStations={billable}
        selectedStationId={station?.id ?? null}
        onSelectStation={(id) => store().selectStation(id)}
        sessionId={sessionId}
        bill={bill.data}
        draft={draft}
        totals={totals}
        entryCaps={entryCaps}
        onCartQty={onCartQty}
        onEntryQty={onEntryQty}
        onTicketQty={onTicketQty}
        onRedeemChange={(points) => store().setRedeemPoints(points)}
        onPlayerNameChange={(name) => store().setPlayerName(name)}
        member={{
          attached:
            draft.memberId === null
              ? null
              : {
                  id: draft.memberId,
                  name: draft.memberName ?? 'Member',
                  points: draft.memberPoints,
                  wallet: draft.memberWallet,
                  auto: !draft.memberTouched,
                },
          query: memberQuery,
          onQueryChange: setMemberQuery,
          results: memberResults(members.data),
          searching: members.isFetching,
          onAttach: onAttachMember,
          onClear: () => store().clearMember(),
        }}
        loyaltyEnabled={loyaltyEnabled}
        paymentSlot={
          totals.subtotal > 0 ? (
            <PaymentSplit
              due={totals.due}
              splits={draft.splits}
              onChange={onSplitsChange}
              issues={splitIssues}
              walletBalance={loyaltyEnabled ? draft.memberWallet : 0}
              walletAvailable={loyaltyEnabled && draft.memberId !== null}
              disabled={settle.isPending}
            />
          ) : null
        }
        onSettle={onSettle}
        canSettle={splitIssues.ok}
        notice={notice}
        busy={setCartLine.isPending || settle.isPending}
        disabled={settle.isPending}
        previewToggle={
          <div className="hidden max-[1279px]:block">
            <Button
              variant="secondary"
              className="w-full"
              data-testid="preview-toggle"
              aria-expanded={previewOpen}
              onClick={() => store().setPreviewOpen(!previewOpen)}
            >
              {previewOpen ? 'Hide ticket preview' : 'Preview'}
            </Button>
          </div>
        }
      />

      <aside
        data-testid="ticket-column"
        data-open={previewOpen}
        className={
          previewOpen
            ? 'flex w-[306px] flex-none flex-col gap-2.5 overflow-auto border-l-2 border-divider bg-neutral-200 p-5'
            : 'flex w-[306px] flex-none flex-col gap-2.5 overflow-auto border-l-2 border-divider bg-neutral-200 p-5 max-[1279px]:hidden'
        }
      >
        <ReceiptPreview
          printJobId={receipt?.printJobId ?? null}
          result={receipt}
          today={venueToday()}
        />
      </aside>
    </div>
  );
}

/* ------------------------------------------------------------- selectors */

/** Consoles a station bill can be taken for: the ones with a live session. */
export function billableStations(stations: Station[] | undefined): Station[] {
  return (stations ?? []).filter((station) => typeof station.session?.id === 'number');
}

/**
 * "one card per OPEN tournament" (docs/tournaments.md §5). LIVE and DONE
 * events are not selling entries — a full one still shows, disabled, because
 * an operator who cannot find the card assumes the screen is broken.
 */
export function openTournaments(tournaments: Tournament[] | undefined): Tournament[] {
  return (tournaments ?? []).filter(
    (tournament) => tournament.status === 'OPEN' && typeof tournament.id === 'number',
  );
}

/** The stock rows the chosen category shows. */
export function visibleItems(items: Item[] | undefined, category: MenuCategory): Item[] {
  const rows = items ?? [];
  if (category === 'ALL') return rows;
  if (category === 'PLAY_TICKET' || category === 'TOURNAMENT') return [];
  return rows.filter((item) => item.category === category);
}

/** The loading state, shaped like the grid it becomes (design.md §1). */
function MenuSkeleton() {
  return (
    <div data-testid="menu-skeleton" aria-busy="true" className="grid grid-cols-4 gap-2.5">
      {[0, 1, 2, 3, 4, 5, 6, 7].map((cell) => (
        <div key={cell} className="min-h-[104px] border-2 border-divider p-3.5">
          <div className="h-2 w-12 bg-track" />
          <div className="mt-3 h-4 w-24 bg-track" />
          <div className="mt-6 h-5 w-16 bg-track" />
        </div>
      ))}
    </div>
  );
}
