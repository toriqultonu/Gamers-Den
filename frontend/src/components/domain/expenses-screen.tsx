'use client';

/**
 * S8 — Expenses (design.md §1, S8): the petty-cash table and the form beside it.
 *
 * Petty cash is the other half of S7's drawer. Every row here subtracts from
 * the expected cash a shift is counted against (`expected = float + cash
 * takings − expenses`), which is why the money cannot leave a till nobody has
 * opened: with no shift open the server refuses both the list and the write,
 * and the screen says so rather than offering a form that cannot post.
 *
 * The voucher is the one option on the form. `?voucher=true` renders and queues
 * the P4 slip **inside the transaction that recorded the row** (design.md §5),
 * so there is never a signed voucher for an expense that was not written — the
 * job number comes back with the row and is shown as confirmation, not promised
 * beforehand.
 */

import { useMemo, useState } from 'react';
import { ReceiptText } from 'lucide-react';
import { AccessNotice } from './access-notice';
import { Button } from '@/components/ui/button';
import { ChipSelect } from '@/components/ui/chip-select';
import { DataTable, type Column } from '@/components/ui/data-table';
import { FieldInput } from '@/components/ui/field-input';
import { StatTile } from '@/components/ui/stat-tile';
import { errorNotice, isApiError } from '@/lib/api';
import { formatAmount, formatBDT, parseAmount } from '@/lib/money';
import { formatVenueTime } from '@/lib/time';
import { readRoster } from '@/features/auth/staff-roster';
import { useSignedInStaff } from '@/features/auth/session';
import { isNoShiftOpen, useExpenses } from '@/features/shift/queries';
import { useRecordExpense } from '@/features/shift/mutations';
import {
  EXPENSE_CATEGORIES,
  EXPENSE_CATEGORY_LABELS,
  createExpenseSchema,
  expenseCategoryLabel,
  expenseTotals,
  fieldError,
  largestCategory,
  recordedBy,
  type Expense,
  type ExpenseCategory,
} from '@/features/shift/schemas';

export function ExpensesScreen() {
  const expenses = useExpenses();
  const staff = useSignedInStaff();

  // Names for the "Recorded by" column. There is no roster endpoint a cashier
  // may read, so this is who the terminal has seen sign in (staff-roster.ts).
  const known = useMemo(
    () => new Map(readRoster().map((entry) => [entry.id, entry.name])),
    [],
  );

  if (isApiError(expenses.error) && expenses.error.status === 403) {
    return <AccessNotice screen="Expenses" />;
  }

  const noShift = isNoShiftOpen(expenses.error);
  const rows = expenses.data ?? [];
  const totals = expenseTotals(rows);
  const largest = largestCategory(rows);

  const columns: Column<Expense>[] = [
    {
      key: 'time',
      header: 'Time',
      width: '84px',
      render: (row) => (
        <span className="tabular opacity-60">
          {row.createdAt ? formatVenueTime(row.createdAt) : '—'}
        </span>
      ),
    },
    {
      key: 'description',
      header: 'Description',
      render: (row) => (
        <span className="font-heading font-extrabold">{row.description ?? '—'}</span>
      ),
    },
    {
      key: 'category',
      header: 'Category',
      width: '120px',
      render: (row) => <span className="opacity-70">{expenseCategoryLabel(row.category)}</span>,
    },
    {
      key: 'staff',
      header: 'Recorded by',
      width: '140px',
      render: (row) => <span className="opacity-70">{recordedBy(row.staffId, known, staff)}</span>,
    },
    {
      key: 'amount',
      header: 'Amount',
      align: 'right',
      width: '120px',
      render: (row) => <span className="tabular">{formatBDT(row.amount ?? 0)}</span>,
    },
  ];

  return (
    <div data-testid="expenses-screen" className="flex min-h-0 flex-1">
      <div className="flex min-w-0 flex-1 flex-col gap-4 overflow-auto p-5">
        <div className="grid grid-cols-3 border-2 border-divider divide-x-2 divide-divider">
          <StatTile
            label="Petty cash this shift"
            value={formatBDT(totals.total)}
            hint="Deducted from the expected drawer on Shift close"
          />
          <StatTile label="Entries" value={formatAmount(totals.count)} />
          <StatTile
            label="Largest category"
            value={largest ? expenseCategoryLabel(largest.category) : '—'}
            hint={largest ? formatBDT(largest.amount) : 'Nothing recorded yet'}
          />
        </div>

        {noShift ? (
          <p
            role="status"
            data-testid="expenses-no-shift"
            className="border-2 border-accent px-3 py-2 text-body text-accent-strong"
          >
            No shift is open on this terminal. Money cannot leave a till nobody has opened — open
            the shift on Shift close first.
          </p>
        ) : expenses.isError ? (
          <p
            role="alert"
            data-testid="expenses-error"
            className="border-2 border-accent px-3 py-2 text-body text-accent-strong"
          >
            {errorNotice(expenses.error, 'The petty cash could not be read.')}
          </p>
        ) : null}

        {expenses.isPending ? (
          <TableSkeleton />
        ) : (
          <DataTable
            columns={columns}
            rows={rows}
            rowKey={(row) => String(row.id ?? `${row.description}-${row.createdAt}`)}
            caption="Petty cash recorded against this shift"
            empty="No petty cash recorded on this shift yet."
          />
        )}
      </div>

      <aside
        data-testid="expenses-rail"
        className="flex w-[356px] flex-none flex-col gap-3 overflow-auto border-l-2 border-divider bg-surface p-5"
      >
        <ExpenseForm disabled={noShift} />
      </aside>
    </div>
  );
}

/* ------------------------------------------------------------------ the form */

export function ExpenseForm({ disabled = false }: { disabled?: boolean }) {
  const [description, setDescription] = useState('');
  const [amount, setAmount] = useState('');
  const [category, setCategory] = useState<ExpenseCategory | null>(null);
  const [voucher, setVoucher] = useState(false);
  const [touched, setTouched] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [recorded, setRecorded] = useState<Expense | null>(null);

  const record = useRecordExpense();

  const typedAmount = parseAmount(amount);
  const draft = {
    description,
    category: category ?? undefined,
    amount: typedAmount ?? undefined,
    voucher,
  };
  const parsed = createExpenseSchema.safeParse(draft);
  const errorFor = (field: string) =>
    touched && !parsed.success ? fieldError(parsed.error, field) : undefined;
  const amountError =
    touched && amount.trim() !== '' && typedAmount === null
      ? 'Enter the amount as a whole number of taka.'
      : errorFor('amount');

  const submit = () => {
    setTouched(true);
    setNotice(null);
    setRecorded(null);
    const check = createExpenseSchema.safeParse(draft);
    if (!check.success) return;
    record.mutate(check.data, {
      onSuccess: (expense) => {
        setRecorded(expense);
        // Only a written row is cleared; a refusal keeps every field (§4.4).
        setDescription('');
        setAmount('');
        setCategory(null);
        setVoucher(false);
        setTouched(false);
      },
      onError: (error) => setNotice(errorNotice(error, 'The expense was not recorded.')),
    });
  };

  return (
    <div data-testid="expense-form" className="flex flex-col gap-3">
      <h2 className="type-label opacity-55">Record petty cash</h2>

      <FieldInput
        label="Description"
        placeholder="e.g. Water delivery"
        autoComplete="off"
        maxLength={200}
        value={description}
        error={errorFor('description')}
        disabled={disabled}
        onChange={(event) => setDescription(event.target.value)}
      />

      <FieldInput
        label="Amount (৳)"
        inputMode="numeric"
        autoComplete="off"
        placeholder="0"
        value={amount}
        error={amountError}
        disabled={disabled}
        onChange={(event) => setAmount(event.target.value)}
      />

      <div className="flex flex-col gap-1">
        <span className="text-[12px] opacity-70">Category</span>
        <ChipSelect
          label="Category"
          value={category}
          onChange={(next) => setCategory(next)}
          options={EXPENSE_CATEGORIES.map((value) => ({
            value,
            label: EXPENSE_CATEGORY_LABELS[value],
            disabled,
          }))}
        />
        {errorFor('category') ? (
          <p role="alert" className="text-[12px] text-accent-strong">
            {errorFor('category')}
          </p>
        ) : null}
      </div>

      <label className="flex items-start gap-2 text-[13px]">
        <input
          type="checkbox"
          data-testid="voucher"
          className="mt-0.5 size-4 accent-[var(--color-accent)]"
          checked={voucher}
          disabled={disabled}
          onChange={(event) => setVoucher(event.target.checked)}
        />
        <span>
          Print a voucher to sign
          <span className="block text-[11px] opacity-55">
            The P4 slip — date, description, category, amount and a signature line.
          </span>
        </span>
      </label>

      {notice ? (
        <p
          role="alert"
          data-testid="expense-error"
          className="border-2 border-accent px-3 py-2 text-body text-accent-strong"
        >
          {notice}
        </p>
      ) : null}

      <Button
        variant="block"
        data-testid="record-expense"
        loading={record.isPending}
        disabled={disabled || record.isPending}
        onClick={submit}
      >
        Record expense
      </Button>

      {recorded ? (
        <p role="status" data-testid="expense-recorded" className="flex items-start gap-2 text-[12px] opacity-70">
          <ReceiptText aria-hidden="true" className="mt-0.5 size-4 shrink-0" strokeWidth={2} />
          {recorded.printJobId
            ? `${formatBDT(recorded.amount ?? 0)} recorded — voucher queued as print job #${recorded.printJobId}.`
            : `${formatBDT(recorded.amount ?? 0)} recorded.`}
        </p>
      ) : null}

      <div className="h-0.5 bg-divider" />
      <p className="text-[12px] opacity-65">
        Expenses come out of this shift&rsquo;s drawer and off the day&rsquo;s profit — they are on
        the Z report and in the owner&rsquo;s reports the moment they sync.
      </p>
    </div>
  );
}

/* ------------------------------------------------------------------ skeleton */

function TableSkeleton() {
  return (
    <div data-testid="expenses-skeleton" aria-busy="true" className="flex flex-col gap-2">
      <div className="h-6 border-b-2 border-divider" />
      {Array.from({ length: 6 }, (_, row) => (
        <div key={row} className="h-8 border-b border-divider bg-surface opacity-40" />
      ))}
    </div>
  );
}
