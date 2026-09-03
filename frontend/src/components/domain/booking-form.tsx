'use client';

/**
 * BookingForm — docs/design.md §2: "console/member/method chips, time stepper,
 * live bill box, confirm with computed total"; prop `settings`.
 *
 * Pay-first, in one call. There is no draft booking and no hold: `POST
 * /bookings` takes the money, writes the row and queues the P1 receipt with its
 * P7 confirmation in one server transaction, so this form is a single request
 * that either happens or does not (§5.3 — never optimistic; a refusal keeps
 * every field exactly as typed).
 *
 * **The bill box is a preview.** Play time × the console's block rate + the
 * package fee, computed here from the cached rate card and `booking-settings`
 * so the figure moves with the stepper instead of waiting for a round trip.
 * The server prices the slot itself — for the *booked* time, so a morning slot
 * is sold at the morning rate — and that is what is charged. When the two
 * disagree the rail says so with the server's figure (§5.11: never a silent
 * charge).
 *
 * **The start time is venue wall-clock.** The picker reads and writes venue
 * time whatever timezone the terminal is set to, and the value that leaves here
 * is absolute with its offset. An overlap with another booking on the same
 * console is a **warning only** — staff override it, because the token can be
 * seated on any free console of that type (docs/bookings.md §7, design.md §8).
 */

import { useMemo, useState } from 'react';
import { AlertTriangle } from 'lucide-react';
import { MemberSearch } from './member-search';
import { Button } from '@/components/ui/button';
import { ChipSelect } from '@/components/ui/chip-select';
import { FieldInput } from '@/components/ui/field-input';
import { TimeStepper, formatBlocks } from '@/components/ui/time-stepper';
import { errorNotice } from '@/lib/api';
import { formatBDT } from '@/lib/money';
import {
  formatVenueDateTime,
  instantFromVenueLocal,
  serverNow,
  venueLocalInput,
} from '@/lib/time';
import { useDebouncedValue } from '@/lib/use-debounced-value';
import {
  useMemberSearch,
  memberResults,
  blockPriceOf,
  type Member,
  type Pricing,
} from '@/features/pos/queries';
import { useBookings } from '@/features/bookings/queries';
import { useCreateBooking } from '@/features/bookings/mutations';
import {
  bookingBill,
  createBookingSchema,
  driftNotice,
  overlappingBookings,
  totalDrift,
  type BookingSettings,
  type Booking,
} from '@/features/bookings/schemas';
import {
  PAYMENT_METHOD_LABELS,
  PAYMENT_METHODS,
  isMfs,
  type PaymentMethod,
} from '@/features/payments/schemas';
import type { Station } from '@/features/sessions/queries';

/** Long enough to outlast typing, short enough that the list feels live. */
export const MEMBER_SEARCH_DEBOUNCE_MS = 250;

/** The default slot: the next half hour, two hours out — a counter's guess. */
export const DEFAULT_LEAD_MINUTES = 120;

/** Two blocks — the hour the prototype opens the stepper on. */
export const DEFAULT_BLOCKS = 2;

export type BookingFormProps = {
  settings: BookingSettings | undefined;
  stations: Station[] | undefined;
  pricing: Pricing[] | undefined;
  onClose: () => void;
  /** Handed the booking that was taken, so the rail can select it. */
  onBooked: (booking: Booking, notice: string | null) => void;
  now?: number;
};

export function BookingForm({
  settings,
  stations,
  pricing,
  onClose,
  onBooked,
  now,
}: BookingFormProps) {
  const at = now ?? serverNow();
  const rows = stations ?? [];

  const [stationId, setStationId] = useState<number | null>(rows[0]?.id ?? null);
  const [name, setName] = useState('');
  const [phone, setPhone] = useState('');
  const [member, setMember] = useState<Member | null>(null);
  const [memberQuery, setMemberQuery] = useState('');
  const [startLocal, setStartLocal] = useState(() => venueLocalInput(defaultStart(at)));
  const [blocks, setBlocks] = useState(DEFAULT_BLOCKS);
  const [method, setMethod] = useState<PaymentMethod>('CASH');
  const [paymentRef, setPaymentRef] = useState('');
  const [notice, setNotice] = useState<string | null>(null);
  const [submitted, setSubmitted] = useState(false);

  const term = useDebouncedValue(memberQuery, MEMBER_SEARCH_DEBOUNCE_MS);
  const members = useMemberSearch(term);
  // The clash check reads the tab the operator is not looking at, which is the
  // only place live bookings for this console are listed.
  const upcoming = useBookings('upcoming');
  const create = useCreateBooking();

  const station = rows.find((row) => row.id === stationId) ?? null;
  const blockPrice = blockPriceOf(pricing, station?.consoleType);
  const bill = bookingBill(blocks, blockPrice, settings?.packageFee ?? 0);
  const startAt = instantFromVenueLocal(startLocal);

  const clashes = useMemo(
    () => overlappingBookings(upcoming.data ?? [], { stationId, startAt, blocks }),
    [upcoming.data, stationId, startAt, blocks],
  );

  const memberId = member?.id ?? null;

  const parsed = createBookingSchema.safeParse({
    stationId: stationId ?? undefined,
    memberId: memberId ?? undefined,
    name,
    phone: phone.trim() === '' ? undefined : phone,
    startAt: startAt ?? undefined,
    blocks,
    method,
    paymentRef: paymentRef.trim() === '' ? undefined : paymentRef,
  });

  // Field errors only appear once the operator has tried to confirm: a form
  // that scolds while it is still being filled in is noise.
  const showErrors = submitted && !parsed.success;
  const issues = parsed.success ? [] : parsed.error.issues;
  const errorFor = (field: string) =>
    showErrors ? issues.find((issue) => issue.path[0] === field)?.message : undefined;

  const confirm = () => {
    setSubmitted(true);
    setNotice(null);
    if (!parsed.success || create.isPending) return;

    create.mutate(parsed.data, {
      onSuccess: (created) => {
        const booking = created.booking;
        // §5.11: the server prices, and a disagreement is said out loud.
        const charged = totalDrift(bill.total, booking?.total);
        onBooked(booking ?? {}, charged === null ? null : driftNotice(bill.total, charged));
      },
      // PREBOOKING_DISABLED, PAYMENT_REF_REQUIRED, WALLET_INSUFFICIENT — none of
      // them wrote anything, and none of them clears a field (§4.4).
      onError: (error) => setNotice(errorNotice(error, 'The booking was not taken.')),
    });
  };

  const walletAllowed = memberId !== null;

  return (
    <div data-testid="booking-form" className="flex flex-col gap-3">
      <div className="flex items-center">
        <p className="type-label opacity-55">New pre-booking · pay first</p>
        <Button variant="ghost" className="ml-auto" disabled={create.isPending} onClick={onClose}>
          Close
        </Button>
      </div>

      {notice ? (
        <p
          role="alert"
          data-testid="booking-form-notice"
          className="flex items-start gap-2 border-2 border-accent px-3 py-2 text-body text-accent-strong"
        >
          <AlertTriangle aria-hidden="true" className="mt-0.5 size-4 shrink-0" strokeWidth={2} />
          {notice}
        </p>
      ) : null}

      <div className="flex flex-col gap-1.5">
        <span className="text-[12px] opacity-70">Console</span>
        <ChipSelect
          label="Console"
          options={rows.map((row) => ({
            value: String(row.id),
            label: `${row.name ?? 'Console'} · ${row.consoleType ?? ''}`.trim(),
            disabled: row.status === 'MAINTENANCE',
          }))}
          value={stationId === null ? null : String(stationId)}
          onChange={(value) => setStationId(Number(value))}
        />
        {errorFor('stationId') ? (
          <p role="alert" className="text-[12px] text-accent-strong">
            Pick the console this booking holds.
          </p>
        ) : null}
      </div>

      <MemberSearch
        attached={
          member === null || typeof member.id !== 'number'
            ? null
            : {
                id: member.id,
                name: member.name ?? 'Member',
                points: member.points ?? 0,
                wallet: member.wallet ?? 0,
              }
        }
        query={memberQuery}
        onQueryChange={setMemberQuery}
        results={memberResults(members.data)}
        searching={members.isFetching}
        disabled={create.isPending}
        onAttach={(hit) => {
          setMember(hit);
          setName(hit.name ?? '');
          setPhone(hit.phone ?? '');
          setMemberQuery('');
        }}
        onClear={() => {
          setMember(null);
          // The typed name stays: the walk-in standing there is still the
          // customer, they are simply not on file.
          if (method === 'WALLET') setMethod('CASH');
        }}
      />

      <FieldInput
        label="Customer name"
        value={name}
        autoComplete="off"
        placeholder="e.g. Rakib Hossain"
        error={errorFor('name')}
        disabled={create.isPending}
        onChange={(event) => setName(event.target.value)}
      />
      <FieldInput
        label="Phone"
        value={phone}
        autoComplete="off"
        placeholder="+880 1XXX-XXXXXX"
        error={errorFor('phone')}
        disabled={create.isPending}
        onChange={(event) => setPhone(event.target.value)}
      />

      <FieldInput
        label="Start time"
        type="datetime-local"
        value={startLocal}
        data-testid="booking-start"
        error={errorFor('startAt') ? 'Pick a start time in the future.' : undefined}
        hint={startAt ? `Venue time — ${formatVenueDateTime(startAt)}` : 'Venue time (Asia/Dhaka).'}
        disabled={create.isPending}
        onChange={(event) => setStartLocal(event.target.value)}
      />

      {clashes.length > 0 ? (
        <p
          role="status"
          data-testid="booking-overlap-warning"
          className="border-2 border-divider px-3 py-2 text-[12px] opacity-75"
        >
          {`This console already has ${clashes.length === 1 ? 'a booking' : `${clashes.length} bookings`} over that time (${clashes
            .map((row) => `${row.name ?? 'a customer'} at ${formatVenueDateTime(row.startAt ?? '')}`)
            .join(', ')}). You can take this one anyway — the token can be seated on any free console of the same type.`}
        </p>
      ) : null}

      <div className="flex flex-col gap-1.5">
        <span className="text-[12px] opacity-70">Play time</span>
        <TimeStepper
          blocks={blocks}
          onChange={setBlocks}
          max={48}
          disabled={create.isPending}
        />
      </div>

      <div data-testid="booking-bill-box" className="flex flex-col gap-1.5 border-2 border-divider p-3">
        <Row label={`Play time · ${formatBlocks(blocks)}`} value={formatBDT(bill.play)} />
        <Row label="Package fee" value={formatBDT(bill.packageFee)} />
        <div className="h-px bg-divider" />
        <div className="flex justify-between font-heading text-[18px] font-extrabold">
          <span>Total, paid now</span>
          <span data-testid="booking-total" className="tabular">
            {formatBDT(bill.total)}
          </span>
        </div>
        {!bill.priced ? (
          <p className="text-[11px] opacity-55">
            The rate card has not answered yet — the server prices this booking at confirm.
          </p>
        ) : null}
      </div>

      <div className="flex flex-col gap-1.5">
        <span className="text-[12px] opacity-70">Paid by</span>
        <ChipSelect
          label="Paid by"
          options={PAYMENT_METHODS.map((value) => ({
            value,
            label: PAYMENT_METHOD_LABELS[value],
            // The server refuses a wallet payment with no member behind it.
            disabled: value === 'WALLET' && !walletAllowed,
          }))}
          value={method}
          onChange={(value) => setMethod(value)}
        />
      </div>

      {isMfs(method) ? (
        <FieldInput
          label="TrxID"
          value={paymentRef}
          autoComplete="off"
          hint="Recorded on the transaction — bKash and Nagad are entered by hand."
          error={errorFor('paymentRef')}
          disabled={create.isPending}
          onChange={(event) => setPaymentRef(event.target.value)}
        />
      ) : null}

      <Button
        variant="block"
        size="lg"
        data-testid="booking-confirm"
        loading={create.isPending}
        onClick={confirm}
      >
        {`Take ${formatBDT(bill.total)} & confirm booking`}
      </Button>

      <p className="text-[12px] opacity-55">
        {`The booking is confirmed only once the full amount is taken. Check-in loads the prepaid time onto the console; more time can be added there with +30 min. A cancellation up to ${
          settings?.cancelCutoffHours ?? 0
        } h before the start refunds everything.`}
      </p>
    </div>
  );
}

/** The next half hour a booking is worth offering, in server time. */
export function defaultStart(at: number, leadMinutes = DEFAULT_LEAD_MINUTES): number {
  const half = 30 * 60_000;
  return Math.ceil((at + leadMinutes * 60_000) / half) * half;
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between text-[13px]">
      <span>{label}</span>
      <span className="tabular">{value}</span>
    </div>
  );
}
