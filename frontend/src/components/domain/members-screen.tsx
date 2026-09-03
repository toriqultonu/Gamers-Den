'use client';

/**
 * S6 — Members: the directory, the member rail, and the two wallet writes
 * (design.md §1, S6 · S6a).
 *
 * The search box is the screen. `GET /members` with no `q` lists everyone by
 * name, so the table opens full rather than empty and narrows as the operator
 * types — one settled search per pause, not one per keystroke
 * (`useDebouncedValue`), which is what keeps a phone number typed at the
 * counter from firing eleven LIKE queries.
 *
 * The rail is the member: wallet, points, the visits they have made and the
 * bookings they hold. Both wallet actions open a dialog rather than firing on
 * the button, because both move money and both have a refusal worth reading —
 * `INSUFFICIENT_POINTS` for a redemption past the balance above all. Neither
 * is optimistic: the figure on screen is the ledger's, and it changes when the
 * server says it changed.
 */

import { useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import { AccessNotice } from './access-notice';
import { NewMemberDialog } from './new-member-dialog';
import { RedeemStepper } from './redeem-stepper';
import { Button } from '@/components/ui/button';
import { ChipSelect } from '@/components/ui/chip-select';
import { DataTable, type Column } from '@/components/ui/data-table';
import { Dialog } from '@/components/ui/dialog';
import { FieldInput } from '@/components/ui/field-input';
import { Tag } from '@/components/ui/tag';
import { formatBlocks } from '@/components/ui/time-stepper';
import { errorNotice, isApiError } from '@/lib/api';
import { formatBDT, parseAmount } from '@/lib/money';
import { useDebouncedValue } from '@/lib/use-debounced-value';
import { formatDuration, formatVenueDateTime, venueToday } from '@/lib/time';
import { memberRows, useMemberDetail, useMemberDirectory } from '@/features/members/queries';
import { useRedeemPoints, useTopUpWallet } from '@/features/members/mutations';
import {
  TOPUP_METHODS,
  TOPUP_METHOD_LABELS,
  isMfs,
  maxRedeemablePoints,
  memberSince,
  playsSummary,
  topupSchema,
  type Member,
  type MemberBooking,
  type MemberDetail,
  type MemberVisit,
  type TopupMethod,
} from '@/features/members/schemas';
import { useStations } from '@/features/sessions/queries';
import { useAppStore } from '@/features/pos/bill-store';

/** Long enough to outlast typing, short enough that the table feels live. */
export const SEARCH_DEBOUNCE_MS = 250;

/** The rungs the top-up dialog offers before the operator types their own. */
export const TOPUP_PRESETS = [500, 1000, 2000] as const;

export function MembersScreen() {
  const router = useRouter();
  const store = useAppStore.getState;

  const [query, setQuery] = useState('');
  const term = useDebouncedValue(query, SEARCH_DEBOUNCE_MS);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [dialog, setDialog] = useState<'new' | 'topup' | 'redeem' | null>(null);

  const directory = useMemberDirectory(term);
  const detail = useMemberDetail(selectedId);
  const stations = useStations();

  const rows = useMemo(() => memberRows(directory.data), [directory.data]);
  const today = venueToday();

  // A 403 on the directory refuses the screen itself — there is nothing behind
  // it (design.md §1: an API 403 renders as an access notice).
  if (isApiError(directory.error) && directory.error.status === 403) {
    return <AccessNotice screen="Members" />;
  }

  const searching = term.trim() !== '';

  return (
    <div data-testid="members-screen" className="flex min-h-0 flex-1">
      <div className="flex min-w-0 flex-1 flex-col gap-4 overflow-auto p-5">
        <div className="flex items-end gap-2.5">
          <FieldInput
            label="Search by name or phone number"
            className="min-w-[280px]"
            value={query}
            autoComplete="off"
            data-testid="member-search-input"
            onChange={(event) => setQuery(event.target.value)}
          />
          <Button variant="primary" onClick={() => setDialog('new')}>
            New member
          </Button>
        </div>

        {directory.isError ? (
          <p
            role="alert"
            data-testid="members-error"
            className="border-2 border-accent px-3 py-2 text-body text-accent-strong"
          >
            {errorNotice(directory.error, 'The member directory could not be read.')}
          </p>
        ) : null}

        {directory.isPending ? (
          <TableSkeleton />
        ) : (
          <DataTable
            caption="Members"
            columns={memberColumns(today)}
            rows={rows}
            rowKey={(member) => String(member.id)}
            selectedKey={selectedId === null ? null : String(selectedId)}
            onSelect={(member) => setSelectedId(member.id ?? null)}
            empty={
              <span data-testid="members-empty">
                {searching
                  ? `No member matches “${term.trim()}” — register them with New member.`
                  : 'No members yet — register the first one with New member.'}
              </span>
            }
          />
        )}
      </div>

      <aside className="flex w-[356px] flex-none flex-col gap-3.5 overflow-auto border-l-2 border-divider bg-surface p-5">
        {selectedId === null ? (
          <p data-testid="member-rail-idle" className="text-body opacity-65">
            Pick a member to see their wallet, points and visits — or register a new one.
          </p>
        ) : detail.isPending ? (
          <RailSkeleton />
        ) : detail.isError ? (
          <p role="alert" data-testid="member-rail-error" className="text-body text-accent-strong">
            {errorNotice(detail.error, 'That member could not be read.')}
          </p>
        ) : detail.data ? (
          <MemberRail
            member={detail.data}
            today={today}
            onTopUp={() => setDialog('topup')}
            onRedeem={() => setDialog('redeem')}
          />
        ) : null}
      </aside>

      <NewMemberDialog
        open={dialog === 'new'}
        onClose={() => setDialog(null)}
        stations={stations.data}
        onSaved={(member, seated) => {
          setDialog(null);
          setSelectedId(member.id ?? null);
          if (!seated) return;
          // The session is live on a console — the operator's next move is on
          // the Floor, with that console already selected.
          const seat = (stations.data ?? []).find((station) => station.floorState === 'FREE');
          if (typeof seat?.id === 'number') store().selectStation(seat.id);
          router.push('/floor');
        }}
      />

      {selectedId !== null && detail.data ? (
        <>
          <TopUpDialog
            open={dialog === 'topup'}
            member={detail.data}
            onClose={() => setDialog(null)}
          />
          <RedeemDialog
            open={dialog === 'redeem'}
            member={detail.data}
            onClose={() => setDialog(null)}
          />
        </>
      ) : null}
    </div>
  );
}

/* ---------------------------------------------------------------- table */

/**
 * The directory columns. `Member` carries no visit count or lifetime total —
 * those live on the detail read — so the table shows what the row actually
 * knows and the rail shows the history.
 */
export function memberColumns(today: string): Column<Member>[] {
  return [
    {
      key: 'name',
      header: 'Member',
      render: (member) => (
        <span className="font-heading font-extrabold">{member.name ?? '—'}</span>
      ),
    },
    { key: 'phone', header: 'Phone', render: (member) => <span className="opacity-70">{member.phone ?? '—'}</span> },
    { key: 'plays', header: 'Plays', render: (member) => <span className="opacity-70">{playsSummary(member)}</span> },
    { key: 'wallet', header: 'Wallet', align: 'right', render: (member) => formatBDT(member.wallet ?? 0) },
    { key: 'points', header: 'Points', align: 'right', render: (member) => String(member.points ?? 0) },
    {
      key: 'since',
      header: 'Since',
      align: 'right',
      render: (member) => <span className="opacity-55">{memberSince(member.createdAt, today)}</span>,
    },
  ];
}

function TableSkeleton() {
  return (
    <div data-testid="members-skeleton" className="flex flex-col gap-2" aria-hidden="true">
      <div className="h-6 border-b-2 border-divider" />
      {Array.from({ length: 8 }, (_, row) => (
        <div key={row} className="h-8 border-b border-divider bg-surface opacity-40" />
      ))}
    </div>
  );
}

/* ----------------------------------------------------------------- rail */

function MemberRail({
  member,
  today,
  onTopUp,
  onRedeem,
}: {
  member: MemberDetail;
  today: string;
  onTopUp: () => void;
  onRedeem: () => void;
}) {
  const points = maxRedeemablePoints(member);
  const visits = member.visits ?? [];
  const bookings = member.bookings ?? [];

  return (
    <div data-testid="member-rail" className="flex flex-col gap-3.5">
      <div>
        <p className="type-label text-accent-strong">{memberSince(member.createdAt, today)}</p>
        <h2 className="font-heading text-[28px] leading-tight font-extrabold tracking-tight">
          {member.name ?? 'Member'}
        </h2>
        <p className="text-[12px] opacity-60">{member.phone ?? '—'}</p>
        <p className="text-[12px] opacity-60">{playsSummary(member)}</p>
      </div>

      <div className="flex flex-col gap-1 bg-text p-4 text-bg">
        <span className="type-label opacity-70">Prepaid wallet</span>
        <span
          data-testid="member-wallet"
          className="font-heading text-[40px] leading-none font-extrabold tracking-tight tabular"
        >
          {formatBDT(member.wallet ?? 0)}
        </span>
        <span className="text-[12px] opacity-70">Session fees deduct automatically</span>
      </div>

      <div className="grid grid-cols-2 gap-2">
        <Button variant="secondary" onClick={onTopUp}>
          Top up
        </Button>
        <Button
          variant="secondary"
          disabled={points === 0}
          onClick={onRedeem}
          data-testid="open-redeem"
        >
          Redeem points
        </Button>
      </div>

      <div className="h-0.5 bg-divider" />

      <dl className="grid grid-cols-2 gap-3">
        <Figure label="Loyalty points" value={String(points)} />
        <Figure label="Points are worth" value={formatBDT(points)} />
      </dl>

      <div className="h-0.5 bg-divider" />

      <h3 className="type-label opacity-55">Recent visits</h3>
      {visits.length === 0 ? (
        <p data-testid="member-no-visits" className="text-[12px] opacity-55">
          No visits yet.
        </p>
      ) : (
        <ul className="flex flex-col">
          {visits.map((visit) => (
            <VisitRow key={visit.sessionId ?? `${visit.startedAt}`} visit={visit} />
          ))}
        </ul>
      )}

      <h3 className="type-label opacity-55">Bookings</h3>
      {bookings.length === 0 ? (
        <p data-testid="member-no-bookings" className="text-[12px] opacity-55">
          No bookings yet.
        </p>
      ) : (
        <ul className="flex flex-col">
          {bookings.map((booking) => (
            <BookingRow key={booking.bookingId ?? `${booking.startAt}`} booking={booking} />
          ))}
        </ul>
      )}
    </div>
  );
}

function VisitRow({ visit }: { visit: MemberVisit }) {
  return (
    <li className="flex justify-between gap-3 border-b border-divider pb-2 pt-2 text-[13px]">
      <span className="min-w-0">
        <span className="block">
          {visit.startedAt ? formatVenueDateTime(visit.startedAt) : '—'}
          {visit.stationName ? ` · ${visit.stationName}` : ''}
        </span>
        <span className="block text-[11px] opacity-55">
          {formatDuration(visit.playedSeconds ?? 0)}
          {visit.consoleType ? ` · ${visit.consoleType}` : ''}
        </span>
      </span>
      <span className="tabular">{formatBlocks(visit.blocks ?? 0)}</span>
    </li>
  );
}

function BookingRow({ booking }: { booking: MemberBooking }) {
  return (
    <li className="flex justify-between gap-3 border-b border-divider pb-2 pt-2 text-[13px]">
      <span className="min-w-0">
        <span className="block">
          {booking.startAt ? formatVenueDateTime(booking.startAt) : '—'}
          {booking.stationName ? ` · ${booking.stationName}` : ''}
        </span>
        <span className="flex items-center gap-1.5 text-[11px] opacity-55">
          {formatBlocks(booking.blocks ?? 0)}
          {typeof booking.tokenNo === 'number' ? <Tag variant="outline">{`#${booking.tokenNo}`}</Tag> : null}
        </span>
      </span>
      <span className="text-right">
        <span className="block tabular">{formatBDT(booking.total ?? 0)}</span>
        <span className="block text-[11px] opacity-55">{booking.status ?? ''}</span>
      </span>
    </li>
  );
}

function RailSkeleton() {
  return (
    <div data-testid="member-rail-skeleton" aria-hidden="true" className="flex flex-col gap-3">
      <div className="h-8 w-2/3 bg-bg opacity-40" />
      <div className="h-24 bg-bg opacity-40" />
      <div className="h-9 bg-bg opacity-40" />
    </div>
  );
}

function Figure({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="type-label opacity-55">{label}</dt>
      <dd className="font-heading text-[22px] font-extrabold tabular">{value}</dd>
    </div>
  );
}

/* -------------------------------------------------------- wallet dialogs */

function TopUpDialog({
  open,
  member,
  onClose,
}: {
  open: boolean;
  member: MemberDetail;
  onClose: () => void;
}) {
  const [amount, setAmount] = useState('500');
  const [method, setMethod] = useState<TopupMethod>('CASH');
  const [paymentRef, setPaymentRef] = useState('');
  const [notice, setNotice] = useState<string | null>(null);
  const topUp = useTopUpWallet();

  const parsed = topupSchema.safeParse({
    amount: parseAmount(amount) ?? 0,
    method,
    paymentRef,
  });
  const amountError =
    amount.trim() === '' || parsed.success
      ? undefined
      : 'Enter a whole number of taka, at least ৳1.';

  const confirm = () => {
    if (!parsed.success || topUp.isPending || typeof member.id !== 'number') return;
    setNotice(null);
    topUp.mutate(
      { memberId: member.id, ...parsed.data },
      {
        onSuccess: () => onClose(),
        // The typed figures stay exactly as they are (§4.4).
        onError: (error) => setNotice(errorNotice(error, 'The top-up was not taken.')),
      },
    );
  };

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={`Top up ${member.name ?? 'member'}`}
      description="Money in — one wallet ledger row, one idempotency key."
    >
      <div data-testid="topup-dialog" className="flex flex-col gap-3">
        {notice ? (
          <p
            role="alert"
            data-testid="topup-notice"
            className="border-2 border-accent px-3 py-2 text-body text-accent-strong"
          >
            {notice}
          </p>
        ) : null}

        <ChipSelect
          label="Amount"
          options={TOPUP_PRESETS.map((value) => ({ value: String(value), label: formatBDT(value) }))}
          value={TOPUP_PRESETS.map(String).includes(amount) ? amount : null}
          onChange={(value) => setAmount(value)}
        />
        <FieldInput
          label="Amount (৳)"
          value={amount}
          inputMode="numeric"
          autoComplete="off"
          error={amountError}
          onChange={(event) => setAmount(event.target.value)}
        />
        <div className="flex flex-col gap-1.5">
          <span className="text-[12px] opacity-70">Paid by</span>
          <ChipSelect
            label="Paid by"
            options={TOPUP_METHODS.map((value) => ({ value, label: TOPUP_METHOD_LABELS[value] }))}
            value={method}
            onChange={(value) => setMethod(value)}
          />
        </div>
        {isMfs(method) ? (
          <FieldInput
            label="TrxID"
            value={paymentRef}
            autoComplete="off"
            hint="Written on the wallet ledger row."
            onChange={(event) => setPaymentRef(event.target.value)}
          />
        ) : null}

        <p className="text-[13px] opacity-70">
          {`Wallet goes to ${formatBDT((member.wallet ?? 0) + (parsed.success ? parsed.data.amount : 0))}.`}
        </p>

        <div className="flex items-center justify-end gap-2">
          <Button variant="ghost" disabled={topUp.isPending} onClick={onClose}>
            Cancel
          </Button>
          <Button
            variant="primary"
            loading={topUp.isPending}
            disabled={!parsed.success}
            onClick={confirm}
          >
            {parsed.success ? `Add ${formatBDT(parsed.data.amount)}` : 'Add to wallet'}
          </Button>
        </div>
      </div>
    </Dialog>
  );
}

function RedeemDialog({
  open,
  member,
  onClose,
}: {
  open: boolean;
  member: MemberDetail;
  onClose: () => void;
}) {
  const max = maxRedeemablePoints(member);
  const [points, setPoints] = useState(0);
  const [notice, setNotice] = useState<string | null>(null);
  const redeem = useRedeemPoints();

  const chosen = Math.min(points, max);

  const confirm = () => {
    if (chosen < 1 || redeem.isPending || typeof member.id !== 'number') return;
    setNotice(null);
    redeem.mutate(
      { memberId: member.id, points: chosen },
      {
        onSuccess: () => onClose(),
        // `INSUFFICIENT_POINTS` — the balance moved under the operator. The
        // dialog keeps the choice and the notice explains why it was refused.
        onError: (error) => setNotice(errorNotice(error, 'The points were not converted.')),
      },
    );
  };

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title="Redeem points to wallet"
      description="1 point = ৳1. The points leave the balance and the wallet gains the same."
    >
      <div data-testid="redeem-dialog" className="flex flex-col gap-3">
        {notice ? (
          <p
            role="alert"
            data-testid="redeem-notice"
            className="border-2 border-accent px-3 py-2 text-body text-accent-strong"
          >
            {notice}
          </p>
        ) : null}

        <p className="text-[13px] opacity-70">{`${member.name ?? 'This member'} holds ${max} points.`}</p>

        <RedeemStepper max={max} value={chosen} onChange={setPoints} disabled={redeem.isPending} />

        <p className="text-[13px]">
          {chosen > 0
            ? `${chosen} points → ${formatBDT(chosen)} into the wallet.`
            : 'Choose how many points to convert.'}
        </p>

        <div className="flex items-center justify-end gap-2">
          <Button variant="ghost" disabled={redeem.isPending} onClick={onClose}>
            Cancel
          </Button>
          <Button
            variant="primary"
            loading={redeem.isPending}
            disabled={chosen < 1}
            onClick={confirm}
          >
            {chosen > 0 ? `Redeem ${chosen} pts` : 'Redeem'}
          </Button>
        </div>
      </div>
    </Dialog>
  );
}
