'use client';

/**
 * RedeemStepper — docs/design.md §2: "None / 100 / 200 / Max", `max, value,
 * onChange`.
 *
 * Points are worth ৳1 each and redemption is a discount on this bill, capped
 * at min(points, total) (api-contract.md, Members). The cap is the whole point
 * of the control: the rungs above it are not rendered, so the operator cannot
 * offer a customer a discount the settle would refuse.
 */

import { ChipSelect, type ChipOption } from '@/components/ui/chip-select';
import { redeemSteps } from '@/features/pos/bill-math';

export type RedeemStepperProps = {
  /** min(member points, bill subtotal) — `billTotals().maxRedeem`. */
  max: number;
  value: number;
  onChange: (points: number) => void;
  disabled?: boolean;
  className?: string;
};

export function RedeemStepper({ max, value, onChange, disabled = false, className }: RedeemStepperProps) {
  const steps = redeemSteps(max);
  const options: ChipOption<string>[] = steps.map((step) => ({
    value: String(step.value),
    label: step.label,
    disabled,
  }));

  return (
    <div data-testid="redeem-stepper" data-max={max} className={className}>
      <ChipSelect
        label="Redeem points"
        options={options}
        value={String(Math.min(value, max))}
        onChange={(next) => onChange(Number(next))}
      />
    </div>
  );
}
