'use client';

/**
 * S6a — New member.
 *
 * Register a customer, optionally open their wallet with a top-up, and
 * optionally walk them straight to a console: "Save & seat on «Nexus»" starts
 * the session and hands the floor over (design.md §1, S6a).
 *
 * That is up to three server calls behind two buttons, and the order matters —
 * the member has to exist before money can go into their wallet, and the wallet
 * before a seat that will draw on it. So they run in sequence and each one is
 * allowed to fail on its own:
 *
 *  - **register** refused with `DUPLICATE_PHONE` puts the message under the
 *    phone field and leaves everything typed exactly where it is. This customer
 *    is already on file; the operator closes the dialog and searches for them.
 *  - **top-up** refused *after* the member was created does not roll the member
 *    back — there is no such endpoint and no such wish. The dialog says the
 *    member is saved and the top-up is not, and the retry skips the creation
 *    that already happened rather than sending it again for a second
 *    `DUPLICATE_PHONE`.
 *  - **seat** refused (`STATION_BUSY` — somebody reached that console first)
 *    leaves a saved member and no session, which is exactly the truth.
 *
 * Nothing here is optimistic. A member who might not exist is worse than a
 * half-second wait.
 */

import { useEffect, useMemo, useState } from 'react';
import { Button } from '@/components/ui/button';
import { ChipSelect } from '@/components/ui/chip-select';
import { Dialog } from '@/components/ui/dialog';
import { FieldInput } from '@/components/ui/field-input';
import { errorNotice, hasErrorCode } from '@/lib/api';
import { formatBDT, parseAmount } from '@/lib/money';
import { useCreateMember, useTopUpWallet } from '@/features/members/mutations';
import {
  PREFERRED_CONSOLES,
  TOPUP_METHODS,
  TOPUP_METHOD_LABELS,
  createMemberSchema,
  fieldError,
  isMfs,
  topupSchema,
  type Member,
  type PreferredConsole,
  type TopupMethod,
} from '@/features/members/schemas';
import { useStartSession } from '@/features/sessions/mutations';
import type { Station } from '@/features/sessions/queries';

/** The prototype's favourites row — free text on the server, chips at the desk. */
export const GAME_SUGGESTIONS = [
  'FIFA 25',
  'Call of Duty',
  'Tekken 8',
  'Gran Turismo',
  'God of War',
  'Mortal Kombat',
] as const;

/** The prototype's opening-wallet rungs; 0 means "no top-up". */
export const OPENING_TOPUPS = [0, 500, 1000, 2000] as const;

/**
 * The console "Save & seat" offers.
 *
 * Only a genuinely free one: `isSeatable` also counts BOOKED, because that
 * console has a checked-in arrival waiting for it — seating a walk-in there
 * takes the seat somebody already paid for.
 */
export function firstFreeStation(stations: Station[] | undefined): Station | null {
  return (stations ?? []).find((station) => station.floorState === 'FREE') ?? null;
}

export type NewMemberDialogProps = {
  open: boolean;
  onClose: () => void;
  /** The floor, for the seat button. */
  stations: Station[] | undefined;
  /**
   * The member was registered. `seated` is true when a session was started for
   * them too, which is the screen's cue to hand over to the Floor.
   */
  onSaved: (member: Member, seated: boolean) => void;
};

type Draft = {
  name: string;
  phone: string;
  console: PreferredConsole | null;
  games: string[];
  topup: number;
  method: TopupMethod;
  paymentRef: string;
};

const EMPTY: Draft = {
  name: '',
  phone: '',
  console: 'PS5',
  games: [],
  topup: 500,
  method: 'CASH',
  paymentRef: '',
};

export function NewMemberDialog({ open, onClose, stations, onSaved }: NewMemberDialogProps) {
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [phoneError, setPhoneError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  /** Set once `POST /members` has succeeded — a retry must not register twice. */
  const [saved, setSaved] = useState<Member | null>(null);
  /**
   * And once the opening top-up has. Each step is remembered separately because
   * a retry after a refused *seat* must not re-run the two writes that already
   * landed: the key for a settled top-up is released on success, so sending it
   * again would credit the wallet a second time rather than replay the first.
   */
  const [toppedUp, setToppedUp] = useState(false);

  const createMember = useCreateMember();
  const topUp = useTopUpWallet();
  const startSession = useStartSession();

  const free = useMemo(() => firstFreeStation(stations), [stations]);
  const busy = createMember.isPending || topUp.isPending || startSession.isPending;

  // A fresh dialog every time it opens: the last registration's figures are
  // nobody's default.
  useEffect(() => {
    if (!open) return;
    setDraft(EMPTY);
    setPhoneError(null);
    setNotice(null);
    setSaved(null);
    setToppedUp(false);
  }, [open]);

  const set = <K extends keyof Draft>(key: K, value: Draft[K]) =>
    setDraft((previous) => ({ ...previous, [key]: value }));

  const parsed = createMemberSchema.safeParse({
    name: draft.name,
    phone: draft.phone,
    preferredConsole: draft.console ?? undefined,
    games: draft.games.length > 0 ? draft.games : undefined,
  });
  const [showErrors, setShowErrors] = useState(false);
  const errors = showErrors && !parsed.success ? parsed.error : null;

  /* ------------------------------------------------------------- the save */

  const save = async (seat: boolean) => {
    if (busy) return;
    setShowErrors(true);
    setNotice(null);
    setPhoneError(null);
    if (!parsed.success) return;

    // Registration, unless a previous attempt already got that far.
    let member = saved;
    if (!member) {
      try {
        member = await createMember.mutateAsync(parsed.data);
        setSaved(member);
      } catch (error) {
        if (hasErrorCode(error, 'DUPLICATE_PHONE')) {
          setPhoneError(errorNotice(error));
        } else {
          setNotice(errorNotice(error, 'The member was not registered.'));
        }
        return;
      }
    }

    const memberId = member.id;
    if (typeof memberId !== 'number') {
      setNotice('The member was registered but came back without an id — reopen them from the table.');
      return;
    }

    // The opening top-up, when there is one. A refusal here leaves a registered
    // member with an empty wallet — true, and topping up from the rail is the
    // way back.
    if (draft.topup > 0 && !toppedUp) {
      const money = topupSchema.safeParse({
        amount: parseAmount(String(draft.topup)) ?? 0,
        method: draft.method,
        paymentRef: draft.paymentRef,
      });
      if (!money.success) {
        setNotice('The opening top-up amount is not a whole number of taka.');
        return;
      }
      try {
        await topUp.mutateAsync({ memberId, ...money.data });
        setToppedUp(true);
      } catch (error) {
        setNotice(
          errorNotice(error, 'The member is saved — the opening top-up did not go through.'),
        );
        return;
      }
    }

    // The seat. `useStartSession` invalidates the stations and the sessions, so
    // the Floor this hands over to is already re-reading.
    if (seat) {
      if (!free || typeof free.id !== 'number') {
        setNotice('No free console to seat them on — the member is saved.');
        return;
      }
      try {
        await startSession.mutateAsync({ stationId: free.id, memberId });
      } catch (error) {
        setNotice(errorNotice(error, 'The member is saved — the console could not be started.'));
        return;
      }
    }

    onSaved(member, seat);
  };

  const seatLabel = free ? `Save & seat on ${free.name ?? `#${free.id}`}` : 'No free console';

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title="New member"
      description="Registers to the venue directory · wallet and points start here"
      className="w-[min(620px,100%)]"
    >
      <div data-testid="new-member" className="flex flex-col gap-4">
        {notice ? (
          <p
            role="alert"
            data-testid="new-member-notice"
            className="border-2 border-accent px-3 py-2 text-body text-accent-strong"
          >
            {notice}
          </p>
        ) : null}

        <div className="grid grid-cols-2 gap-3.5">
          <FieldInput
            label="Full name"
            value={draft.name}
            placeholder="e.g. Rifat Hasan"
            autoComplete="off"
            error={fieldError(errors, 'name')}
            onChange={(event) => set('name', event.target.value)}
          />
          <FieldInput
            label="Phone number"
            value={draft.phone}
            placeholder="+880 1XXX-XXXXXX"
            autoComplete="off"
            inputMode="tel"
            // The server's own refusal, under the field that caused it.
            error={phoneError ?? fieldError(errors, 'phone')}
            onChange={(event) => {
              setPhoneError(null);
              set('phone', event.target.value);
            }}
          />
        </div>

        <div className="flex flex-col gap-1.5">
          <span className="text-[12px] opacity-70">Preferred console</span>
          <ChipSelect
            label="Preferred console"
            options={PREFERRED_CONSOLES.map((value) => ({ value, label: value }))}
            value={draft.console}
            onChange={(value) => set('console', value)}
          />
        </div>

        <div className="flex flex-col gap-1.5">
          <span className="text-[12px] opacity-70">Games they play</span>
          <ChipSelect
            multiple
            label="Games they play"
            options={GAME_SUGGESTIONS.map((value) => ({ value, label: value }))}
            value={draft.games}
            onChange={(games) => set('games', games)}
          />
        </div>

        <div className="h-0.5 bg-divider" />

        <div className="grid grid-cols-2 gap-3.5">
          <div className="flex flex-col gap-1.5">
            <span className="text-[12px] opacity-70">Opening wallet top-up</span>
            <ChipSelect
              label="Opening wallet top-up"
              options={OPENING_TOPUPS.map((value) => ({
                value: String(value),
                label: value === 0 ? 'None' : formatBDT(value),
              }))}
              value={String(draft.topup)}
              onChange={(value) => set('topup', Number(value))}
            />
          </div>
          {draft.topup > 0 ? (
            <div className="flex flex-col gap-1.5">
              <span className="text-[12px] opacity-70">Paid by</span>
              <ChipSelect
                label="Paid by"
                options={TOPUP_METHODS.map((value) => ({
                  value,
                  label: TOPUP_METHOD_LABELS[value],
                }))}
                value={draft.method}
                onChange={(value) => set('method', value)}
              />
              {isMfs(draft.method) ? (
                <FieldInput
                  label="TrxID"
                  value={draft.paymentRef}
                  autoComplete="off"
                  hint="Written on the wallet ledger row."
                  onChange={(event) => set('paymentRef', event.target.value)}
                />
              ) : null}
            </div>
          ) : null}
        </div>

        <div className="flex items-center gap-5 bg-surface p-4">
          <div>
            <div className="type-label opacity-55">Wallet opens at</div>
            <div className="font-heading text-[28px] font-extrabold tracking-tight tabular">
              {formatBDT(draft.topup)}
            </div>
          </div>
          <p className="max-w-[260px] text-[12px] opacity-65">
            {draft.topup > 0
              ? 'Session fees deduct from the wallet automatically on every visit. Points earn at 1 per ৳20 spent.'
              : 'They can top up any time at the desk. Points earn at 1 per ৳20 spent.'}
          </p>
        </div>

        <div className="flex items-center gap-2.5 border-t-2 border-divider pt-3.5">
          <Button
            variant="primary"
            size="lg"
            loading={busy}
            disabled={busy}
            onClick={() => void save(false)}
          >
            {saved ? 'Retry' : 'Save member'}
          </Button>
          <Button
            variant="secondary"
            disabled={busy || !free}
            onClick={() => void save(true)}
            data-testid="save-and-seat"
          >
            {seatLabel}
          </Button>
          <Button variant="ghost" className="ml-auto" disabled={busy} onClick={onClose}>
            Cancel
          </Button>
        </div>
      </div>
    </Dialog>
  );
}
