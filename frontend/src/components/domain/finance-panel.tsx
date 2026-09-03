'use client';

/**
 * FinancePanel — docs/tournaments.md §6: four stats and the verdict line, in
 * the manager rail.
 *
 * **Manager+ only, and the endpoint is the authority.** `GET
 * /tournaments/{id}/finance` 403s a cashier token and is never embedded in a
 * shared payload — so this panel is mounted only where the query is, and a
 * cashier's terminal never asks the question (design.md §1, S12: "Cashier:
 * config controls absent; finance endpoint 403").
 *
 * Every figure is the server's arithmetic, not a recomputation: revenue =
 * entries × fee, netProfit = revenue − prize pool, opportunityCost = (N−1) ×
 * matchDuration/60 × the allocated consoles' average hourly rate, extraMargin =
 * netProfit − opportunityCost. The formula line spells out where the
 * opportunity cost came from, because that is the number an owner argues with.
 */

import { cn } from '@/components/ui/cn';
import { errorNotice } from '@/lib/api';
import { formatBDT } from '@/lib/money';
import { financeFormula, financeVerdict, type TournamentFinance } from '@/features/tournaments/schemas';

export type FinancePanelProps = {
  finance: TournamentFinance | undefined;
  loading?: boolean;
  error?: unknown;
};

export function FinancePanel({ finance, loading = false, error }: FinancePanelProps) {
  return (
    <section data-testid="finance-panel" className="flex flex-col gap-2.5">
      <h3 className="type-label text-accent-strong">Financial analytics · managers only</h3>

      {error ? (
        <p role="alert" className="text-[12px] text-accent-strong">
          {errorNotice(error, 'The event finances could not be read.')}
        </p>
      ) : null}

      {loading && !finance ? (
        <div aria-busy="true" className="h-16 border-2 border-divider opacity-40" />
      ) : null}

      {finance ? (
        <>
          <dl className="grid grid-cols-2 gap-2.5">
            <Stat label="Revenue" value={formatBDT(finance.revenue ?? 0)} />
            <Stat label="Net after prize" value={formatBDT(finance.netProfit ?? 0)} />
            <Stat label="Opportunity cost" value={formatBDT(finance.opportunityCost ?? 0)} />
            <Stat
              label="Extra margin"
              testId="finance-extra"
              value={formatBDT(finance.extraMargin ?? 0, { sign: 'always' })}
              className={cn(
                'text-[22px] tracking-[-0.03em]',
                (finance.extraMargin ?? 0) >= 0 ? 'text-accent-strong' : 'text-text',
              )}
            />
          </dl>
          <p className="text-[11px] opacity-55">{financeFormula(finance)}</p>
          <p data-testid="finance-verdict" className="text-[12px] opacity-75">
            {financeVerdict(finance)}
          </p>
        </>
      ) : null}
    </section>
  );
}

function Stat({
  label,
  value,
  testId,
  className,
}: {
  label: string;
  value: string;
  testId?: string;
  className?: string;
}) {
  return (
    <div>
      <dt className="type-label opacity-55">{label}</dt>
      <dd
        data-testid={testId}
        className={cn('font-heading text-[18px] font-extrabold tabular', className)}
      >
        {value}
      </dd>
    </div>
  );
}
