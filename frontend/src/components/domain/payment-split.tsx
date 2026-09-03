'use client';

/**
 * PaymentSplit — docs/design.md §2: variants "cash, bkash, nagad, wallet";
 * states "selected, amounts"; props `due, methods, onChange`.
 *
 * The tender half of S4's bill rail. Three rules are visible in the markup
 * because each of them is a 409 the operator would otherwise meet after the
 * money is on the counter:
 *
 *  - **the rows must sum to what is due** — `SPLIT_MISMATCH`. The remainder
 *    line says how far off it is and in which direction, and the Settle button
 *    stays down until it is nothing.
 *  - **bKash and Nagad need a TrxID** — `PAYMENT_REF_REQUIRED`. MVP is a manual
 *    reference (`verifyState: MANUAL`); the verified phase-2 flow is behind
 *    config and deliberately not built here (api-contract.md, "bKash / Nagad").
 *  - **the wallet cannot go past its balance** — `WALLET_INSUFFICIENT`, and it
 *    needs a member on the bill to draw from at all.
 *
 * Presentational: every rule is evaluated in `features/payments/schemas.ts` and
 * arrives as `issues`, every edit calls back up with the whole next array. The
 * component holds no state, which is what lets a failed settle re-render it
 * with the operator's own figures still in the fields.
 */

import { Button } from '@/components/ui/button';
import { FieldInput } from '@/components/ui/field-input';
import { formatBDT } from '@/lib/money';
import {
  PAYMENT_METHODS,
  PAYMENT_METHOD_LABELS,
  draftAmount,
  effectiveSplits,
  isMfs,
  setSplitAmount,
  setSplitRef,
  toggleSplitMethod,
  type PaymentMethod,
  type PaymentSplitDraft,
  type SplitIssues,
} from '@/features/payments/schemas';

export type PaymentSplitProps = {
  /** What has to be tendered — `billTotals().due`. */
  due: number;
  /** The draft rows, straight from the store. Empty renders the cash default. */
  splits: readonly PaymentSplitDraft[];
  onChange: (splits: PaymentSplitDraft[]) => void;
  /** Every rule's verdict, from `validateSplits`. */
  issues: SplitIssues;
  /** The member's balance, and 0 when there is no member to draw from. */
  walletBalance?: number;
  /** False on a counter sale: `POST /payments` carries no member there. */
  walletAvailable?: boolean;
  disabled?: boolean;
};

export function PaymentSplit({
  due,
  splits,
  onChange,
  issues,
  walletBalance = 0,
  walletAvailable = false,
  disabled = false,
}: PaymentSplitProps) {
  const rows = effectiveSplits(splits, due);
  const on = (method: PaymentMethod) => rows.some((row) => row.method === method);

  return (
    <section data-testid="payment-split" className="flex flex-col gap-2.5">
      <h3 className="type-label opacity-55">Split the payment</h3>

      <div role="group" aria-label="Payment methods" className="grid grid-cols-4 gap-1.5">
        {PAYMENT_METHODS.map((method) => {
          const selected = on(method);
          const unavailable = method === 'WALLET' && !walletAvailable;
          return (
            <Button
              key={method}
              variant={selected ? 'primary' : 'secondary'}
              size="sm"
              data-testid={`split-method-${method}`}
              data-state={selected ? 'selected' : 'unselected'}
              aria-pressed={selected}
              disabled={disabled || unavailable}
              title={
                unavailable
                  ? 'Attach a member to a station bill to pay from their wallet.'
                  : undefined
              }
              className="px-1 py-2 text-[12px]"
              onClick={() => onChange(toggleSplitMethod(splits, method, due))}
            >
              {PAYMENT_METHOD_LABELS[method]}
            </Button>
          );
        })}
      </div>

      {rows.map((row) => {
        const unreadable = issues.unreadable.includes(row.method);
        const refMissing = issues.missingRef.includes(row.method);
        const walletOver = row.method === 'WALLET' && issues.walletOver;
        return (
          <div key={row.method} data-testid={`split-row-${row.method}`} className="flex flex-col gap-1.5">
            <FieldInput
              label={PAYMENT_METHOD_LABELS[row.method]}
              inputMode="numeric"
              autoComplete="off"
              className="tabular"
              value={row.amount}
              disabled={disabled}
              suffix="৳"
              error={
                unreadable
                  ? 'Whole taka only.'
                  : walletOver
                    ? `The wallet holds ${formatBDT(walletBalance)}.`
                    : undefined
              }
              hint={
                row.method === 'WALLET' && !walletOver
                  ? `Wallet balance ${formatBDT(walletBalance)}`
                  : undefined
              }
              onChange={(event) =>
                onChange(setSplitAmount(splits, row.method, event.target.value, due))
              }
            />
            {isMfs(row.method) ? (
              <FieldInput
                label={`${PAYMENT_METHOD_LABELS[row.method]} TrxID`}
                data-testid={`split-ref-${row.method}`}
                autoComplete="off"
                value={row.paymentRef}
                disabled={disabled}
                // Mirrors 409 PAYMENT_REF_REQUIRED — shown under the field the
                // operator is looking at, not as a banner after the fact.
                error={refMissing ? 'Enter the bKash/Nagad TrxID.' : undefined}
                hint={
                  refMissing
                    ? undefined
                    : 'Typed from the customer’s confirmation — the last 6 print on the ticket.'
                }
                onChange={(event) =>
                  onChange(setSplitRef(splits, row.method, event.target.value, due))
                }
              />
            ) : null}
          </div>
        );
      })}

      <p
        data-testid="split-remainder"
        data-balanced={issues.balanced || undefined}
        className={
          issues.balanced
            ? 'flex justify-between text-[12px] opacity-60'
            : 'flex justify-between text-[12px] text-accent-strong'
        }
      >
        <span>{remainderLabel(issues)}</span>
        <span className="tabular">{formatBDT(Math.abs(issues.remainder))}</span>
      </p>
    </section>
  );
}

/** Which way the split is out, in the operator's words. */
export function remainderLabel(issues: SplitIssues): string {
  if (issues.unreadable.length > 0) return 'Check the amounts';
  if (issues.remainder > 0) return 'Left to tender';
  if (issues.remainder < 0) return 'Over-tendered';
  return 'Tendered';
}

/** The amount one method is carrying — for the caller and for the tests. */
export function amountOf(
  splits: readonly PaymentSplitDraft[],
  method: PaymentMethod,
  due: number,
): number {
  const row = effectiveSplits(splits, due).find((entry) => entry.method === method);
  return row ? (draftAmount(row) ?? 0) : 0;
}
