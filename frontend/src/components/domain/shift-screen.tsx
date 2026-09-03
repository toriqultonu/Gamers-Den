'use client';

/**
 * S7 — Shift close (design.md §1, S7; §5 P2/P3).
 *
 * The screen answers one question in four steps, top to bottom: what this shift
 * took (the X matrix), what of that was tournament entries and pre-bookings
 * (the two reconciliation strips), what should therefore be in the drawer, and
 * what actually is. The last step is the only place the operator types, and the
 * discrepancy under their fingers updates as they do — the arithmetic they
 * would otherwise do on the back of a receipt.
 *
 * **That live figure is a preview, not the Z.** The server recomputes expected,
 * counted and discrepancy inside the closing transaction and writes those onto
 * the shift; what is on screen is the same subtraction done early. Nothing here
 * is optimistic (§5.3): closing snapshots the Z, queues the P2 job, raises the
 * discrepancy alert and signs the operator out of the terminal in one
 * transaction, so the screen waits for it and then renders **the server's own
 * report** before handing the terminal back to S1.
 */

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { AlertTriangle, Printer } from 'lucide-react';
import { AccessNotice } from './access-notice';
import { Button } from '@/components/ui/button';
import { DataTable, type Column } from '@/components/ui/data-table';
import { FieldInput } from '@/components/ui/field-input';
import { StatTile } from '@/components/ui/stat-tile';
import { cn } from '@/components/ui/cn';
import { errorNotice, isApiError } from '@/lib/api';
import { formatAmount, formatBDT, parseAmount } from '@/lib/money';
import { formatVenueTime, formatVenueDateTime } from '@/lib/time';
import { useSession } from '@/features/auth/session';
import { useBookingSettings } from '@/features/bookings/queries';
import { isNoShiftOpen, useCurrentShiftReport } from '@/features/shift/queries';
import { useCloseShift, useOpenShift, usePrintXReport } from '@/features/shift/mutations';
import {
  closeShiftSchema,
  discrepancyNote,
  discrepancyOf,
  discrepancyValue,
  drawerState,
  expectedWorking,
  expenseCategoryLabel,
  fieldError,
  methodLabel,
  openShiftSchema,
  postingCount,
  reconciliationStrips,
  takingsRows,
  takingsTotals,
  type ShiftExpenseLine,
  type ShiftReport,
  type ShiftTakingsRow,
} from '@/features/shift/schemas';

export function ShiftScreen() {
  const report = useCurrentShiftReport();
  const settings = useBookingSettings();

  /** The Z the server answered with — the terminal is signing out behind it. */
  const [closed, setClosed] = useState<ShiftReport | null>(null);

  if (closed) return <ClosedPanel report={closed} />;

  if (isApiError(report.error) && report.error.status === 403) {
    return <AccessNotice screen="Shift close" />;
  }

  if (report.isPending) return <ShiftSkeleton />;

  if (isNoShiftOpen(report.error)) return <NoShiftPanel />;

  if (report.isError || !report.data) {
    return (
      <section className="m-6 flex max-w-xl flex-col gap-3 border-2 border-divider p-6">
        <p role="alert" data-testid="shift-error" className="text-body text-accent-strong">
          {errorNotice(report.error, 'The shift report could not be read.')}
        </p>
        <Button variant="secondary" onClick={() => void report.refetch()}>
          Try again
        </Button>
      </section>
    );
  }

  return (
    <ShiftDesk
      report={report.data}
      prebookingEnabled={settings.data?.enabled !== false}
      onClosed={setClosed}
    />
  );
}

/* ------------------------------------------------------------------ the desk */

function ShiftDesk({
  report,
  prebookingEnabled,
  onClosed,
}: {
  report: ShiftReport;
  prebookingEnabled: boolean;
  onClosed: (report: ShiftReport) => void;
}) {
  const [counted, setCounted] = useState('');
  const [note, setNote] = useState('');
  const [notice, setNotice] = useState<string | null>(null);

  const close = useCloseShift();
  const printX = usePrintXReport();

  const expected = report.cash?.expected ?? 0;
  const countedCash = parseAmount(counted);
  const discrepancy = discrepancyOf(expected, countedCash);
  const state = drawerState(discrepancy);

  const parsed = closeShiftSchema.safeParse({
    countedCash: countedCash ?? undefined,
    handoverNote: note,
  });
  const [touched, setTouched] = useState(false);
  const countError = !touched
    ? undefined
    : counted.trim() !== '' && countedCash === null
      ? 'Enter the counted cash as a whole number of taka.'
      : parsed.success
        ? undefined
        : fieldError(parsed.error, 'countedCash');

  const rows = takingsRows(report.takings);
  const totals = takingsTotals(report.takings);
  const strips = reconciliationStrips(report.takings, { prebookingEnabled });
  const expenseLines = report.expenses?.lines ?? [];

  const submit = () => {
    setTouched(true);
    setNotice(null);
    const check = closeShiftSchema.safeParse({
      countedCash: countedCash ?? undefined,
      handoverNote: note,
    });
    if (!check.success) return;
    close.mutate(
      { countedCash: check.data.countedCash, handoverNote: check.data.handoverNote },
      {
        onSuccess: onClosed,
        // The count and the note survive the refusal — the drawer is still
        // counted, and the operator only has to press again (§4.4).
        onError: (error) => setNotice(errorNotice(error, 'The shift was not closed.')),
      },
    );
  };

  return (
    <div data-testid="shift-screen" className="flex min-h-0 flex-1">
      <div className="flex min-w-0 flex-1 flex-col gap-5 overflow-auto p-5">
        <div className="grid grid-cols-4 border-2 border-divider divide-x-2 divide-divider">
          <StatTile
            label="Shift"
            value={`#${report.shiftId ?? '—'}`}
            hint={report.terminal ?? undefined}
          />
          <StatTile
            label="Opened"
            value={report.openedAt ? formatVenueTime(report.openedAt) : '—'}
            hint={report.openedAt ? formatVenueDateTime(report.openedAt) : undefined}
          />
          <StatTile label="Opening float" value={formatBDT(report.openingFloat ?? 0)} />
          <StatTile
            label="Transactions"
            value={formatAmount(postingCount(report.takings))}
            hint={`${report.takings?.refundCount ?? 0} refunds · ${report.takings?.pointsRedeemed ?? 0} pts redeemed`}
          />
        </div>

        <section className="flex flex-col gap-2">
          <h2 className="type-label opacity-55">X report — takings by method</h2>
          <TakingsTable rows={rows} totals={totals} />
        </section>

        {strips.map((strip) => (
          <section
            key={strip.id}
            data-testid={`strip-${strip.id}`}
            className={cn(
              'flex items-center gap-4 border-2 px-4 py-3',
              strip.id === 'tournament' ? 'border-accent' : 'border-divider',
            )}
          >
            <div className="flex-1">
              <p
                className={cn(
                  'type-label',
                  strip.id === 'tournament' ? 'text-accent-strong' : 'opacity-55',
                )}
              >
                {strip.label}
              </p>
              <p className="text-[12px] opacity-65">{strip.note}</p>
            </div>
            <p className="font-heading text-[24px] font-extrabold tabular">
              {formatBDT(strip.amount)}
            </p>
          </section>
        ))}

        <section
          data-testid="drawer"
          data-state={state}
          className="grid grid-cols-3 border-2 border-text divide-x-2 divide-divider"
        >
          <StatTile
            label="Expected in drawer"
            value={formatBDT(expected)}
            hint={expectedWorking(report.cash)}
          />
          <StatTile
            label="Counted by cashier"
            value={countedCash === null ? '—' : formatBDT(countedCash)}
            hint={countedCash === null ? 'Not counted yet' : 'Typed at the counter'}
          />
          <StatTile
            variant={state === 'over' || state === 'short' ? 'accent' : 'default'}
            label="Discrepancy"
            value={<span data-testid="discrepancy">{discrepancyValue(discrepancy)}</span>}
            hint={discrepancyNote(discrepancy)}
          />
        </section>

        <PettyCashList lines={expenseLines} total={report.expenses?.total ?? 0} />
      </div>

      <aside
        data-testid="shift-rail"
        className="flex w-[356px] flex-none flex-col gap-3 overflow-auto border-l-2 border-divider bg-surface p-5"
      >
        <h2 className="type-label opacity-55">Count the drawer</h2>

        <FieldInput
          label="Notes & coins counted (৳)"
          inputMode="numeric"
          autoComplete="off"
          placeholder="0"
          value={counted}
          error={countError}
          onChange={(event) => setCounted(event.target.value)}
        />

        <label className="flex flex-col gap-1">
          <span className="text-[12px] opacity-70">Handover note</span>
          <textarea
            className="min-h-[72px] w-full rounded-none border border-divider bg-surface px-2.5 py-1.5 text-body text-text caret-accent focus-visible:border-accent focus-visible:outline-2 focus-visible:outline-accent"
            placeholder="Anything the next shift should know"
            maxLength={500}
            value={note}
            onChange={(event) => setNote(event.target.value)}
          />
        </label>

        <div className="h-0.5 bg-divider" />

        {notice ? (
          <p
            role="alert"
            data-testid="close-error"
            className="border-2 border-accent px-3 py-2 text-body text-accent-strong"
          >
            {notice}
          </p>
        ) : null}

        {state === 'over' || state === 'short' ? (
          <p
            role="status"
            data-testid="discrepancy-warning"
            className="flex items-start gap-2 border-2 border-accent px-3 py-2 text-[12px] text-accent-strong"
          >
            <AlertTriangle aria-hidden="true" className="mt-0.5 size-4 shrink-0" strokeWidth={2} />
            {`Closing ${formatBDT(Math.abs(discrepancy ?? 0))} ${state} raises a cash alert for the owner.`}
          </p>
        ) : null}

        <Button
          variant="secondary"
          data-testid="print-x"
          loading={printX.isPending}
          onClick={() => printX.mutate()}
        >
          <Printer aria-hidden="true" className="size-4" strokeWidth={2} />
          Print interim X report
        </Button>
        {printX.isError ? (
          <p role="alert" className="text-[12px] text-accent-strong">
            {errorNotice(printX.error, 'The X report did not print.')}
          </p>
        ) : null}
        {printX.isSuccess ? (
          <p role="status" className="text-[12px] opacity-65">
            {`X report queued${printX.data?.printJobId ? ` — print job #${printX.data.printJobId}` : ''}. Nothing was closed.`}
          </p>
        ) : null}

        <Button
          variant="block"
          size="lg"
          className="mt-auto"
          data-testid="close-shift"
          loading={close.isPending}
          disabled={countedCash === null || close.isPending}
          onClick={submit}
        >
          Print Z report &amp; close shift
        </Button>
        <p className="text-[12px] opacity-55">
          Closing prints the Z, signs you out of this terminal and returns to the login screen.
        </p>
      </aside>
    </div>
  );
}

/* ---------------------------------------------------------------- the matrix */

export function TakingsTable({
  rows,
  totals,
}: {
  rows: readonly Required<ShiftTakingsRow>[];
  totals: Required<ShiftTakingsRow>;
}) {
  const money = (value: number) => (
    <span className="tabular">{value === 0 ? '—' : formatBDT(value)}</span>
  );

  const columns: Column<Required<ShiftTakingsRow>>[] = [
    {
      key: 'method',
      header: 'Method',
      render: (row) => <span className="font-heading font-extrabold">{methodLabel(row.method)}</span>,
    },
    { key: 'gaming', header: 'Gaming', align: 'right', render: (row) => money(row.gaming) },
    { key: 'fnb', header: 'Food & bev', align: 'right', render: (row) => money(row.fnb) },
    { key: 'tournament', header: 'Tournament', align: 'right', render: (row) => money(row.tournament) },
    { key: 'booking', header: 'Pre-booking', align: 'right', render: (row) => money(row.booking) },
    {
      key: 'total',
      header: 'Total',
      align: 'right',
      render: (row) => (
        <span className="font-heading font-extrabold tabular">{formatBDT(row.total)}</span>
      ),
    },
  ];

  return (
    <div data-testid="takings" className="flex flex-col">
      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(row) => row.method}
        caption="Takings by tender method and category"
      />
      <div
        data-testid="takings-total"
        className="flex items-center justify-between border-b-2 border-text px-2 py-2"
      >
        <span className="font-heading text-[15px] font-extrabold">Total takings</span>
        <span className="font-heading text-[15px] font-extrabold tabular">
          {formatBDT(totals.total)}
        </span>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------- the petty cash */

function PettyCashList({ lines, total }: { lines: readonly ShiftExpenseLine[]; total: number }) {
  return (
    <section data-testid="petty-cash" className="flex flex-col gap-2">
      <div className="flex items-baseline justify-between">
        <h2 className="type-label opacity-55">Petty cash out of this drawer</h2>
        <span className="font-heading text-[15px] font-extrabold tabular">{formatBDT(total)}</span>
      </div>
      {lines.length === 0 ? (
        <p className="border-2 border-divider p-4 text-[13px] opacity-60">
          No petty cash out of this drawer yet.
        </p>
      ) : (
        <ul className="flex flex-col">
          {lines.map((line) => (
            <li
              key={line.id ?? `${line.description}-${line.at}`}
              className="flex items-center justify-between gap-3 border-b border-divider py-2"
            >
              <span className="w-16 shrink-0 text-[12px] tabular opacity-60">
                {line.at ? formatVenueTime(line.at) : '—'}
              </span>
              <span className="flex-1 font-heading text-[13px] font-extrabold">
                {line.description ?? '—'}
              </span>
              <span className="text-[12px] opacity-70">{expenseCategoryLabel(line.category)}</span>
              <span className="w-24 text-right text-body tabular">
                {formatBDT(-(line.amount ?? 0))}
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

/* ----------------------------------------------------------------- the close */

/**
 * What the terminal shows for the moment between the Z and S1: the server's own
 * closing figures, then the sign-out the close already performed server-side.
 *
 * The redirect is not cosmetic. `ShiftService.close` revokes this operator's
 * refresh family on this terminal, so the session in the browser is already
 * dead — leaving the app open would mean the next 401 does it messily instead.
 */
function ClosedPanel({ report }: { report: ShiftReport }) {
  const { signOut } = useSession();
  const router = useRouter();

  useEffect(() => {
    void signOut().catch(() => router.replace('/login'));
  }, [signOut, router]);

  const discrepancy = report.cash?.discrepancy ?? 0;

  return (
    <section
      data-testid="shift-closed"
      className="m-6 flex max-w-xl flex-col gap-3 border-2 border-text p-6"
    >
      <p className="type-label text-accent-strong">Shift closed</p>
      <h2 className="text-h2">{`Shift #${report.shiftId ?? ''} is closed`}</h2>
      <dl className="grid grid-cols-3 gap-4 tabular">
        <div>
          <dt className="type-label opacity-55">Expected</dt>
          <dd className="font-heading text-h3 font-extrabold">
            {formatBDT(report.cash?.expected ?? 0)}
          </dd>
        </div>
        <div>
          <dt className="type-label opacity-55">Counted</dt>
          <dd className="font-heading text-h3 font-extrabold">
            {formatBDT(report.cash?.counted ?? 0)}
          </dd>
        </div>
        <div>
          <dt className="type-label opacity-55">Discrepancy</dt>
          <dd className="font-heading text-h3 font-extrabold">
            {formatBDT(discrepancy, { sign: discrepancy === 0 ? 'auto' : 'always' })}
          </dd>
        </div>
      </dl>
      <p className="text-body opacity-75">
        {report.printJobId
          ? `The Z report is printing as job #${report.printJobId}. Signing out of this terminal…`
          : 'Signing out of this terminal…'}
      </p>
    </section>
  );
}

/* ------------------------------------------------------------- no shift open */

/**
 * The empty state, which on this screen has an action attached: a terminal with
 * no open shift cannot take money at all, and `POST /shifts` is reachable from
 * nowhere else in the app.
 */
function NoShiftPanel() {
  const [float, setFloat] = useState('');
  const [touched, setTouched] = useState(false);
  const open = useOpenShift();

  const openingFloat = parseAmount(float);
  const parsed = openShiftSchema.safeParse({ openingFloat: openingFloat ?? undefined });
  const error = !touched
    ? undefined
    : float.trim() !== '' && openingFloat === null
      ? 'Enter the float as a whole number of taka.'
      : parsed.success
        ? undefined
        : fieldError(parsed.error, 'openingFloat');

  return (
    <section
      data-testid="no-shift"
      className="m-6 flex max-w-md flex-col gap-4 border-2 border-divider p-6"
    >
      <p className="type-label opacity-55">Shift close</p>
      <h2 className="text-h2">No shift is open on this terminal</h2>
      <p className="text-body opacity-75">
        A shift is the drawer, not the person — open one with what is in the till before the first
        sale, and everything sold on this counter reconciles against it.
      </p>
      <FieldInput
        label="Opening float (৳)"
        inputMode="numeric"
        autoComplete="off"
        placeholder="0"
        value={float}
        error={error}
        onChange={(event) => setFloat(event.target.value)}
      />
      {open.isError ? (
        <p role="alert" data-testid="open-error" className="text-body text-accent-strong">
          {errorNotice(open.error, 'The shift was not opened.')}
        </p>
      ) : null}
      <Button
        variant="block"
        data-testid="open-shift"
        loading={open.isPending}
        disabled={openingFloat === null || open.isPending}
        onClick={() => {
          setTouched(true);
          const check = openShiftSchema.safeParse({ openingFloat: openingFloat ?? undefined });
          if (!check.success) return;
          open.mutate({ openingFloat: check.data.openingFloat });
        }}
      >
        Open the shift
      </Button>
    </section>
  );
}

/* ------------------------------------------------------------------ skeleton */

function ShiftSkeleton() {
  return (
    <div data-testid="shift-skeleton" aria-busy="true" className="flex min-h-0 flex-1">
      <div className="flex min-w-0 flex-1 flex-col gap-5 p-5">
        <div className="h-20 border-2 border-divider" />
        <div className="flex flex-col gap-2">
          <div className="h-6 border-b-2 border-divider" />
          {Array.from({ length: 5 }, (_, row) => (
            <div key={row} className="h-8 border-b border-divider bg-surface opacity-40" />
          ))}
        </div>
        <div className="h-24 border-2 border-divider" />
        <div className="h-24 border-2 border-text" />
      </div>
      <div className="w-[356px] flex-none border-l-2 border-divider bg-surface p-5">
        <div className="h-24 border border-divider opacity-40" />
      </div>
    </div>
  );
}
