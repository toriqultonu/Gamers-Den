'use client';

/**
 * S9 — Reports (design.md §1, S9 row): "KPIs, 14-day stacked trend, per-station
 * utilisation, busiest hours, top sellers" — plus the three pre-booking figures
 * docs/bookings.md §6 adds: bookings per day, show-rate, package-fee income.
 *
 * Manager+ (api-contract.md §1). The middleware keeps a cashier off the route
 * and the API 403s regardless; that 403 renders as the access notice, which is
 * the state design.md §1 names for this screen ("Cashier: hidden + guarded").
 *
 * **Every chart owns its empty state.** design.md §1 gives S9 one line —
 * "Not enough data yet" per chart — and it is per chart on purpose: a venue
 * that has taken money but sold no bookings should read a full trend beside an
 * empty booking chart, not a screen-wide apology. The server sends the two
 * facts that decide it (`tradingSeconds`, `bookings.showRatePct`) rather than
 * leaving the client to infer emptiness from zeroes.
 */

import { AccessNotice } from './access-notice';
import { BarChart } from '@/components/ui/bar-chart';
import { DataTable, type Column } from '@/components/ui/data-table';
import { ProgressBar } from '@/components/ui/progress-bar';
import { SegmentedChoice } from '@/components/ui/segmented-choice';
import { StatTile } from '@/components/ui/stat-tile';
import { cn } from '@/components/ui/cn';
import { errorNotice, isApiError } from '@/lib/api';
import { formatAmount, formatBDT } from '@/lib/money';
import { useReport } from '@/features/reports/queries';
import {
  DEFAULT_RANGE,
  NOT_ENOUGH_DATA,
  RANGE_PRESETS,
  TREND_SERIES,
  bookingsPerDay,
  busiestHours,
  hasBookingData,
  hasUtilisationData,
  rangeNote,
  showRateNote,
  showRateValue,
  stackedTrend,
  stationsBusyLabel,
  trendPeak,
  utilisationRows,
  type HourRow,
  type RangeId,
  type Report,
  type TopSeller,
} from '@/features/reports/schemas';
import { useState } from 'react';
import type { Role } from '@/lib/nav';

export type ReportsScreenProps = {
  /** The role the middleware just read from the session cookie. */
  role: Role | null;
};

const CAN_READ: readonly Role[] = ['ADMIN', 'MANAGER'];

export function ReportsScreen({ role }: ReportsScreenProps) {
  const [range, setRange] = useState<RangeId>(DEFAULT_RANGE);
  const allowed = role !== null && CAN_READ.includes(role);
  const report = useReport(range, { enabled: allowed });

  if (!allowed || (isApiError(report.error) && report.error.status === 403)) {
    return <AccessNotice screen="Reports" />;
  }

  const data = report.data;

  return (
    <div
      data-testid="reports-screen"
      className="flex min-h-0 flex-1 flex-col gap-5 overflow-auto p-5"
    >
      <div className="flex items-center gap-3">
        <SegmentedChoice
          label="Range"
          value={range}
          onChange={(next) => setRange(next as RangeId)}
          options={RANGE_PRESETS.map((preset) => ({ value: preset.id, label: preset.label }))}
        />
        <p data-testid="reports-range" className="type-label ml-auto opacity-55">
          {rangeNote(data?.range)}
        </p>
      </div>

      {report.isError ? (
        <p
          role="alert"
          data-testid="reports-error"
          className="border-2 border-accent px-3 py-2 text-body text-accent-strong"
        >
          {errorNotice(report.error, 'The report could not be read.')}
        </p>
      ) : null}

      {report.isPending ? (
        <ReportSkeleton />
      ) : (
        <>
          <div className="grid grid-cols-4 border-2 border-divider divide-x-2 divide-divider">
            <StatTile
              label="Revenue"
              value={formatBDT(data?.kpis?.revenue ?? 0)}
              hint={`${formatAmount(data?.kpis?.sales ?? 0)} sales · ${formatAmount(
                data?.kpis?.sessions ?? 0,
              )} sessions`}
            />
            <StatTile
              label="Avg. ticket"
              value={formatBDT(data?.kpis?.avgTicket ?? 0)}
              hint={`${formatBDT(data?.kpis?.pointsRedeemed ?? 0)} redeemed in points`}
            />
            <StatTile
              label="Petty cash"
              value={formatBDT(data?.kpis?.expenses ?? 0)}
              hint="Recorded on S8, deducted here"
            />
            <StatTile
              variant="accent"
              label="Net profit"
              value={formatBDT(data?.kpis?.netProfit ?? 0)}
              hint="Revenue less petty cash"
            />
          </div>

          <div className="grid grid-cols-[2fr_1fr] gap-5">
            <StackedTrend report={data} />
            <Utilisation report={data} />
          </div>

          <div className="h-0.5 bg-divider" />

          <div className="grid grid-cols-2 gap-5">
            <BusiestHours hours={busiestHours(data?.busiestHours)} stations={stationCount(data)} />
            <TopSellers sellers={data?.topSellers} />
          </div>

          <div className="h-0.5 bg-divider" />

          <Bookings report={data} />
        </>
      )}
    </div>
  );
}

/* --------------------------------------------------------- the trend */

/**
 * The stacked column chart. Four segments, not the prototype's two: the money
 * model has four gross buckets and they sum to what was tendered, so a
 * tournament night drawn as gaming + F&B would look like a quiet one
 * (`features/reports/schemas.ts`, TREND_SERIES).
 *
 * Plain divs — frontend/ARCHITECTURE.md §2 puts charts in "plain SVG/divs", and
 * a stack of four is a column of flex children, not a plot.
 */
function StackedTrend({ report }: { report: Report | undefined }) {
  const days = stackedTrend(report?.trend);
  const peak = trendPeak(days);

  return (
    <section className="flex flex-col gap-3">
      <h2 className="type-label opacity-55">Revenue, stacked by what sold it</h2>

      {peak <= 0 ? (
        <p
          data-testid="trend-empty"
          className="border-2 border-divider p-4 text-[13px] opacity-60"
        >
          {NOT_ENOUGH_DATA}
        </p>
      ) : (
        <>
          <div
            data-testid="trend-chart"
            role="img"
            aria-label="Revenue by day, stacked by category"
            className="flex h-[210px] items-end gap-2 border-b-2 border-text"
          >
            {days.map((day) => (
              <div key={day.key} className="flex h-full flex-1 flex-col justify-end">
                {day.segments.map((segment) => (
                  <span
                    key={segment.key}
                    data-testid="trend-segment"
                    data-series={segment.key}
                    title={`${day.label} · ${segment.key}: ${formatBDT(segment.value)}`}
                    style={{ height: `${(segment.value / peak) * 100}%` }}
                    className={SERIES_FILL[segment.key]}
                  />
                ))}
              </div>
            ))}
          </div>
          <div className="flex gap-2">
            {days.map((day) => (
              <span key={day.key} className="flex-1 text-center text-[10px] opacity-50">
                {day.label}
              </span>
            ))}
          </div>
          <div data-testid="trend-legend" className="flex flex-wrap gap-4 text-[12px]">
            {TREND_SERIES.map((series) => (
              <span key={series.key} className="flex items-center gap-1.5">
                <span aria-hidden="true" className={cn('size-3', SERIES_FILL[series.key])} />
                {series.label}
              </span>
            ))}
          </div>
        </>
      )}
    </section>
  );
}

/**
 * The two design tokens for chart series (`color.accent`, `color.bar-alt`),
 * plus two tints of the accent ramp — design.md §3 gives a second series and no
 * third, so the extra two stay inside the accent ramp rather than inventing
 * colours the palette does not have.
 */
const SERIES_FILL: Record<string, string> = {
  gaming: 'bg-accent',
  fnb: 'bg-bar-alt',
  tournament: 'bg-accent-600',
  booking: 'bg-accent-300',
};

/* --------------------------------------------------- utilisation */

function Utilisation({ report }: { report: Report | undefined }) {
  const rows = utilisationRows(report?.stationUtilisation);

  return (
    <section className="flex flex-col gap-3">
      <h2 className="type-label opacity-55">Station utilisation</h2>
      {!hasUtilisationData(report) ? (
        <p
          data-testid="utilisation-empty"
          className="border-2 border-divider p-4 text-[13px] opacity-60"
        >
          {NOT_ENOUGH_DATA}
        </p>
      ) : (
        <div className="flex flex-col gap-3.5">
          {rows.map((row) => (
            <div key={row.key} data-testid="utilisation-row" className="flex flex-col gap-1">
              <div className="flex justify-between text-[13px]">
                <span className="font-heading font-extrabold">{row.name}</span>
                <span className="tabular opacity-60">{row.pct}%</span>
              </div>
              <ProgressBar value={row.pct} max={100} />
              <span className="text-[11px] opacity-50">{row.note}</span>
            </div>
          ))}
        </div>
      )}
      <p className="text-[11px] opacity-50">
        Measured against the hours a till was open, not the wall clock.
      </p>
    </section>
  );
}

/* ------------------------------------------------- hours & sellers */

function BusiestHours({ hours, stations }: { hours: HourRow[]; stations: number }) {
  const columns: Column<HourRow>[] = [
    { key: 'window', header: 'Window', render: (row) => row.window },
    {
      key: 'busy',
      header: 'Avg. stations busy',
      align: 'right',
      render: (row) => stationsBusyLabel(row.avgStationsBusy, stations),
    },
    {
      key: 'revenue',
      header: 'Revenue',
      align: 'right',
      render: (row) => formatBDT(row.revenue),
    },
  ];

  return (
    <section className="flex flex-col gap-2.5">
      <h2 className="type-label opacity-55">Busiest hours</h2>
      <DataTable
        columns={columns}
        rows={hours}
        rowKey={(row) => row.key}
        caption="The hours that traded, takings first"
        empty={NOT_ENOUGH_DATA}
      />
    </section>
  );
}

function TopSellers({ sellers }: { sellers: readonly TopSeller[] | undefined }) {
  const columns: Column<TopSeller>[] = [
    {
      key: 'name',
      header: 'Item',
      render: (row) => <span className="font-heading font-extrabold">{row.name}</span>,
    },
    { key: 'units', header: 'Units', align: 'right', render: (row) => formatAmount(row.units ?? 0) },
    {
      key: 'revenue',
      header: 'Revenue',
      align: 'right',
      render: (row) => formatBDT(row.revenue ?? 0),
    },
  ];

  return (
    <section className="flex flex-col gap-2.5">
      <h2 className="type-label opacity-55">Top selling items</h2>
      <DataTable
        columns={columns}
        rows={sellers ?? []}
        rowKey={(row) => String(row.itemId ?? row.name)}
        caption="Items by revenue over the range"
        empty={NOT_ENOUGH_DATA}
      />
    </section>
  );
}

/* ------------------------------------------------------- pre-booking */

/**
 * docs/bookings.md §6: "Reports: bookings per day, show-rate, package-fee
 * income."
 *
 * The counts are keyed on the slot's start — this is attendance — while the
 * income figures are keyed on the day the money was taken, so they line up with
 * the pre-booking line on that day's X/Z (`ReportView.BookingsView`). The two
 * are labelled apart for exactly that reason.
 */
function Bookings({ report }: { report: Report | undefined }) {
  const bookings = report?.bookings;
  const perDay = bookingsPerDay(bookings);
  const present = hasBookingData(bookings);

  return (
    <section className="flex flex-col gap-3">
      <h2 className="type-label opacity-55">Pre-booking</h2>

      {!present ? (
        <p
          data-testid="bookings-empty"
          className="border-2 border-divider p-4 text-[13px] opacity-60"
        >
          {NOT_ENOUGH_DATA}
        </p>
      ) : (
        <div className="grid grid-cols-[2fr_1fr] gap-5">
          <div className="flex flex-col gap-2.5">
            <h3 className="type-label opacity-55">Bookings per day</h3>
            <BarChart
              label="Bookings per day, by slot date"
              data={perDay.map((day) => ({ label: day.label, value: day.total }))}
              height={120}
              empty={NOT_ENOUGH_DATA}
            />
            <p className="text-[11px] opacity-50">
              Counted on the day the slot was due, whatever became of it.
            </p>
          </div>

          <div className="grid grid-cols-1 border-2 border-divider divide-y-2 divide-divider">
            <StatTile
              label="Show rate"
              value={showRateValue(bookings)}
              hint={showRateNote(bookings)}
            />
            <StatTile
              label="Package-fee income"
              value={formatBDT(bookings?.packageFeeIncome ?? 0)}
              hint={`${formatBDT(bookings?.playIncome ?? 0)} play time · ${formatBDT(
                bookings?.income ?? 0,
              )} taken`}
            />
          </div>
        </div>
      )}
    </section>
  );
}

/* -------------------------------------------------------------- helpers */

/** The denominator of "2.4 / 4" — every seat the utilisation list carries. */
function stationCount(report: Report | undefined): number {
  return report?.stationUtilisation?.length ?? 0;
}

/** The loading state, shaped like the screen it becomes (design.md §1). */
function ReportSkeleton() {
  return (
    <div data-testid="reports-skeleton" aria-busy="true" className="flex flex-col gap-5">
      <div className="grid grid-cols-4 border-2 border-divider divide-x-2 divide-divider">
        {[0, 1, 2, 3].map((tile) => (
          <div key={tile} className="flex flex-col gap-2 p-4">
            <div className="h-2.5 w-20 bg-track" />
            <div className="h-8 w-24 bg-track" />
            <div className="h-3 w-28 bg-track" />
          </div>
        ))}
      </div>
      <div className="grid grid-cols-[2fr_1fr] gap-5">
        <div className="h-[210px] border-2 border-divider" />
        <div className="h-[210px] border-2 border-divider" />
      </div>
    </div>
  );
}
