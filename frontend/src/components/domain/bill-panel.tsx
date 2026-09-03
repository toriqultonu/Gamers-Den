'use client';

/**
 * BillPanel — docs/design.md §2: variants "station, counter"; states
 * "with/without member, tournament/ticket lines"; props `mode, sessionId?`.
 *
 * The right-hand rail of S4, and the only place in the app where the four
 * things a customer can be charged for meet:
 *
 *   gaming (the session's unbilled blocks, priced by the server)
 * + food & drink (the server cart)
 * + tournament entries (draft — settles as `tournamentEntries[]`)
 * + play tickets (draft — settles as `playTickets[]`)
 * − points redeemed (capped at min(points, subtotal))
 * = due
 *
 * A seated booking's prepaid time shows as a credit note beside that sum, not
 * inside it: the server already left those blocks out of `gamingDue`, so this
 * rail reports what is covered and charges what is not
 * (`features/pos/bill-math.ts`).
 *
 * Presentational on purpose: every number arrives computed
 * (`features/pos/bill-math.ts`) and every control calls back up. The screen
 * owns the mutations, which is what lets the optimistic cart and the
 * never-optimistic settle live side by side without this file knowing — the
 * split panel arrives through `paymentSlot` for the same reason.
 */

import type { ReactNode } from 'react';
import { Button } from '@/components/ui/button';
import { FieldInput } from '@/components/ui/field-input';
import { SegmentedChoice } from '@/components/ui/segmented-choice';
import { ChipSelect } from '@/components/ui/chip-select';
import { cn } from '@/components/ui/cn';
import { formatBDT } from '@/lib/money';
import { formatBlocks } from '@/components/ui/time-stepper';
import { consoleLabel } from './station-card';
import { CartLine } from './cart-line';
import { MemberSearch, type MemberSearchProps } from './member-search';
import { RedeemStepper } from './redeem-stepper';
import { needsPlayerName, playerNameValue, WALK_IN, type BillTotals } from '@/features/pos/bill-math';
import type { BillDraft, PosMode } from '@/features/pos/bill-store';
import type { Bill, Station } from '@/features/sessions/queries';

export type BillPanelProps = {
  mode: PosMode;
  onModeChange: (mode: PosMode) => void;

  /** Consoles with a live session — what a station bill can be taken for. */
  billableStations: Station[];
  selectedStationId: number | null;
  onSelectStation: (stationId: number) => void;

  /** The station bill, when there is a session behind this panel. */
  sessionId?: number | null;
  bill?: Bill;

  draft: BillDraft;
  totals: BillTotals;
  /** Slots left per tournament, so `+` cannot oversell a nearly-full event. */
  entryCaps?: Record<number, number>;

  onCartQty: (itemId: number, qty: number) => void;
  onEntryQty: (tournamentId: number, qty: number) => void;
  onTicketQty: (consoleType: string, blocks: number, qty: number) => void;
  onRedeemChange: (points: number) => void;
  onPlayerNameChange: (name: string) => void;
  member: Omit<MemberSearchProps, 'disabled' | 'className'>;

  /**
   * Whether points and the wallet apply to this bill at all.
   *
   * They do on a station bill, where the member is attached to the session.
   * They do not on a counter sale: `POST /payments` carries no `memberId` and a
   * counter cart has no seat to inherit one from, so the server settles it with
   * no member — "a counter sale: F&B only, and no member … loyalty simply does
   * not apply" (`billing/domain/PaymentService.java`). Offering a redemption
   * there would tender less than is owed and 409 `SPLIT_MISMATCH` every time.
   */
  loyaltyEnabled?: boolean;

  /** The PaymentSplit panel — it sits under the total it has to add up to. */
  paymentSlot?: ReactNode;

  onSettle?: () => void;
  /** False while the split is short, over, or missing a TrxID. */
  canSettle?: boolean;
  /** The last domain error, rendered over an otherwise intact bill. */
  notice?: string | null;
  busy?: boolean;
  disabled?: boolean;
  /** The preview toggle for 1024–1279 (design.md §4). */
  previewToggle?: ReactNode;
};

export function BillPanel({
  mode,
  onModeChange,
  billableStations,
  selectedStationId,
  onSelectStation,
  sessionId,
  bill,
  draft,
  totals,
  entryCaps = {},
  onCartQty,
  onEntryQty,
  onTicketQty,
  onRedeemChange,
  onPlayerNameChange,
  member,
  loyaltyEnabled = true,
  paymentSlot,
  onSettle,
  canSettle = true,
  notice = null,
  busy = false,
  disabled = false,
  previewToggle,
}: BillPanelProps) {
  const station = billableStations.find((row) => row.id === selectedStationId) ?? null;
  const stationMode = mode === 'station';
  // A station bill with nothing behind it: the console has no open session, so
  // there is nothing to bill and nothing to add food to.
  const noSession = stationMode && (sessionId === null || sessionId === undefined);
  const lines = draft.cart?.lines ?? [];

  return (
    <aside data-testid="bill-panel" data-mode={mode} className={panelShell}>
      <SegmentedChoice
        label="Bill"
        value={mode}
        onChange={onModeChange}
        options={[
          { value: 'counter', label: 'Counter sale' },
          { value: 'station', label: station ? `Station · ${station.name}` : 'Station bill' },
        ]}
      />

      {stationMode && billableStations.length > 0 ? (
        <ChipSelect
          label="Console"
          options={billableStations.map((row) => ({
            value: String(row.id),
            label: row.name ?? `#${row.id}`,
          }))}
          value={selectedStationId === null ? null : String(selectedStationId)}
          onChange={(value) => onSelectStation(Number(value))}
        />
      ) : null}

      <div className="flex items-baseline justify-between">
        <h2 className="font-heading text-[24px] font-extrabold tracking-tight">
          {stationMode ? (station?.name ?? 'Station bill') : 'Counter sale'}
        </h2>
        <p className="text-[12px] opacity-60">
          {stationMode ? consoleLabel(station?.consoleType) : 'Walk-up'}
        </p>
      </div>

      <hr className="rule" />

      {notice ? (
        <p role="alert" data-testid="bill-notice" className="border-2 border-accent px-2.5 py-2 text-[12px] text-accent-strong">
          {notice}
        </p>
      ) : null}

      {noSession ? (
        <p data-testid="bill-no-session" className="text-body opacity-70">
          {billableStations.length === 0
            ? 'No console has an open session — start one on the Floor, or take a counter sale.'
            : 'Pick a console with an open session, or take a counter sale.'}
        </p>
      ) : null}

      {stationMode && (bill?.gamingDue ?? 0) > 0 ? (
        <Row
          testId="bill-gaming"
          label={`Gaming · ${formatBlocks(bill?.billableBlocks ?? 0)}`}
          amount={bill?.gamingDue ?? 0}
        />
      ) : null}

      {stationMode && (bill?.tournamentDue ?? 0) > 0 ? (
        <Row testId="bill-tournament-due" label="Tournament entries" amount={bill?.tournamentDue ?? 0} />
      ) : null}

      {lines.map((line) => (
        <CartLine
          key={line.itemId}
          kind="fnb"
          name={line.name ?? `Item #${line.itemId}`}
          qty={line.qty ?? 0}
          lineTotal={line.lineTotal ?? 0}
          disabled={disabled}
          onChange={(qty) => onCartQty(line.itemId as number, qty)}
        />
      ))}

      {draft.entries.map((entry) => (
        <CartLine
          key={`entry-${entry.tournamentId}`}
          kind="entry"
          name={`Entry — ${entry.name}`}
          qty={entry.qty}
          lineTotal={entry.fee * entry.qty}
          max={entryCaps[entry.tournamentId]}
          disabled={disabled}
          onChange={(qty) => onEntryQty(entry.tournamentId, qty)}
        />
      ))}

      {draft.tickets.map((ticket) => (
        <CartLine
          key={`ticket-${ticket.consoleType}-${ticket.blocks}`}
          kind="ticket"
          name={`Play ticket — ${ticket.consoleType} · ${formatBlocks(ticket.blocks)}`}
          qty={ticket.qty}
          lineTotal={ticket.price * ticket.qty}
          disabled={disabled}
          onChange={(qty) => onTicketQty(ticket.consoleType, ticket.blocks, qty)}
        />
      ))}

      {totals.subtotal === 0 && !noSession ? (
        <p data-testid="bill-empty" className="text-body opacity-60">
          Nothing on this bill yet — tap the menu to add.
        </p>
      ) : null}

      <hr className="rule" />
      <Row testId="bill-subtotal" label="Subtotal" amount={totals.subtotal} />

      {needsPlayerName(draft) ? (
        <FieldInput
          data-testid="player-name"
          label="Player name (ticket / bracket)"
          placeholder={`Name on the token — or attach a member below · ${WALK_IN}`}
          value={playerNameValue(draft)}
          disabled={disabled || draft.memberName !== null}
          hint={
            draft.memberName
              ? 'Taken from the attached member.'
              : 'Left blank, the stub prints “Walk-in guest”.'
          }
          onChange={(event) => onPlayerNameChange(event.target.value)}
        />
      ) : null}

      <MemberSearch {...member} disabled={disabled} />

      {draft.memberId !== null && !loyaltyEnabled ? (
        <p data-testid="loyalty-off" className="text-[11px] opacity-55">
          Points and the wallet belong to a station bill — a counter sale settles without a
          member.
        </p>
      ) : null}

      {loyaltyEnabled && draft.memberId !== null ? (
        <RedeemStepper
          max={totals.maxRedeem}
          value={totals.redeem}
          onChange={onRedeemChange}
          disabled={disabled}
        />
      ) : null}

      {loyaltyEnabled ? (
        <Row
          testId="bill-redeem"
          label={totals.redeem > 0 ? `Points redeemed (${totals.redeem} pts)` : 'Points redeemed'}
          amount={-totals.redeem}
        />
      ) : null}

      {totals.credit > 0 ? (
        // Not a deduction: those blocks were paid at the booking or ticket sale
        // and never entered `gamingDue`. Subtracting it here would tender less
        // than `POST /payments` expects (features/pos/bill-math.ts).
        <div data-testid="bill-credit" className="flex justify-between text-body opacity-70">
          <span>Prepaid time · already paid</span>
          <span className="tabular">{formatBDT(totals.credit)}</span>
        </div>
      ) : null}

      <div className="flex items-baseline justify-between font-heading text-[30px] font-extrabold tracking-tighter">
        <span>Due</span>
        <span data-testid="bill-due" className="tabular">
          {formatBDT(totals.due)}
        </span>
      </div>

      {loyaltyEnabled && draft.memberId !== null ? (
        // P1's loyalty line, previewed: "points earned · balance" (design.md §5).
        <p data-testid="bill-points-earned" className="flex justify-between text-[12px] opacity-60">
          <span>Points earned</span>
          <span className="tabular">
            {`${totals.pointsEarned} pts · balance ${draft.memberPoints - totals.redeem + totals.pointsEarned}`}
          </span>
        </p>
      ) : null}

      <hr className="rule" />

      {paymentSlot}

      <div className="mt-auto flex flex-col gap-2 pt-2.5">
        {previewToggle}
        <Button
          variant="primary"
          className="h-12 w-full text-[15px]"
          data-testid="settle"
          disabled={disabled || totals.subtotal === 0 || !onSettle || !canSettle}
          loading={busy}
          onClick={() => onSettle?.()}
        >
          {`Take ${formatBDT(totals.due)} & print`}
        </Button>
      </div>
    </aside>
  );
}

const panelShell =
  'flex w-[348px] flex-none flex-col gap-2.5 overflow-auto border-l-2 border-divider bg-surface p-5';

function Row({ testId, label, amount }: { testId?: string; label: string; amount: number }) {
  return (
    <div data-testid={testId} className={cn('flex justify-between text-body')}>
      <span>{label}</span>
      <span className="tabular">{formatBDT(amount)}</span>
    </div>
  );
}
