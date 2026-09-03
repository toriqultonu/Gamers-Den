'use client';

/**
 * S2 — Overview (design.md §1, S2 row).
 *
 * The owner's screen: "Occupancy, revenue, avg ticket, net profit;
 * horizontally scrolling live-station cards (click → Floor); pre-sold bookings
 * stat; 30-day + day-of-week trends; stock watchlist; staff & shift closes;
 * collapsible alerts rail (bell + unread badge)."
 *
 * It reads three things and invents none of them:
 *
 *  - `GET /overview` — the KPIs, the pre-sold stat, both trends, the watchlist
 *    and the recent closes, folded server-side in one document;
 *  - `GET /stations` — the live cards. The overview endpoint deliberately does
 *    **not** carry them (`OverviewView`): they are the Floor's read, and
 *    shipping them twice would give one screen two truths for the same card;
 *  - `GET /alerts` — the rail.
 *
 * Admin only. The middleware turns a manager or cashier away before this
 * renders (`lib/nav.ts`), and the API answers 403 regardless — which is what
 * the access notice here is for, since a role can change mid-shift and the
 * routing cookie is only a hint (§4.3).
 *
 * The error state is the **stale-data banner** design.md §1 names, not a blank
 * screen: a failed re-read leaves the last good figures on the page and says
 * when they were true, because an owner reading yesterday's numbers knowingly
 * is better served than one reading nothing.
 */

import Link from 'next/link';
import { WifiOff } from 'lucide-react';
import { AccessNotice } from './access-notice';
import { AlertsRail } from './alerts-rail';
import { CountdownClock } from './countdown-clock';
import { consoleLabel } from './station-card';
import { ProgressBar } from '@/components/ui/progress-bar';
import { StatTile } from '@/components/ui/stat-tile';
import { Tag } from '@/components/ui/tag';
import { BarChart } from '@/components/ui/bar-chart';
import { errorNotice, isApiError } from '@/lib/api';
import { formatBDT } from '@/lib/money';
import { formatVenueDateTime, formatVenueTime } from '@/lib/time';
import { stationClockSnapshot, stationVariant, type Station } from '@/features/sessions/schemas';
import { useStations } from '@/features/sessions/queries';
import { useOverview } from '@/features/reports/queries';
import { useSyncStatus } from '@/features/sync/use-sync-status';
import {
  NOT_ENOUGH_DATA,
  avgTicketNote,
  discrepancyNote,
  hasWeekdayData,
  netProfitNote,
  occupancyNote,
  occupancyPct,
  preSoldNote,
  stockWatchRows,
  trendBars,
  trendSummary,
  weekdayRows,
  type ShiftClose,
  type StockWatch,
  type Weekday,
} from '@/features/reports/schemas';
import type { Role } from '@/lib/nav';

export type OverviewScreenProps = {
  /** The role the middleware just read from the session cookie. */
  role: Role | null;
};

export function OverviewScreen({ role }: OverviewScreenProps) {
  const overview = useOverview({ enabled: role === 'ADMIN' });
  const stations = useStations({ enabled: role === 'ADMIN' });
  const sync = useSyncStatus({ enabled: role === 'ADMIN' });

  if (role !== 'ADMIN' || (isApiError(overview.error) && overview.error.status === 403)) {
    return <AccessNotice screen="Overview" />;
  }

  const data = overview.data;
  const today = data?.today;
  // A failed re-read that still has the last good document behind it is stale
  // data, not an outage — the banner says so and the figures stay up.
  const stale = overview.isError && data !== undefined;

  return (
    <div data-testid="overview-screen" className="flex min-h-0 flex-1">
      <div className="flex min-w-0 flex-1 flex-col gap-5 overflow-auto p-5">
        {stale ? <StaleBanner asOf={data?.serverTime} lastSyncedAt={sync.lastSyncedAt} /> : null}

        {overview.isError && !data ? (
          <p
            role="alert"
            data-testid="overview-error"
            className="border-2 border-accent px-3 py-2 text-body text-accent-strong"
          >
            {errorNotice(overview.error, 'Today’s figures could not be read.')}
          </p>
        ) : null}

        {overview.isPending ? (
          <TilesSkeleton />
        ) : (
          <>
            <div className="grid grid-cols-5 border-2 border-divider divide-x-2 divide-divider">
              <StatTile
                label="Occupancy now"
                value={occupancyPct(data?.occupancy)}
                hint={occupancyNote(data?.occupancy)}
              />
              <StatTile
                label="Revenue today"
                value={formatBDT(today?.revenue ?? 0)}
                hint={`${formatBDT(today?.gaming ?? 0)} gaming · ${formatBDT(today?.fnb ?? 0)} F&B`}
              />
              <StatTile
                label="Avg. ticket"
                value={formatBDT(today?.avgTicket ?? 0)}
                hint={avgTicketNote(today)}
              />
              {/* docs/bookings.md §6 — money taken for play not yet delivered. */}
              <StatTile
                label="Pre-sold"
                value={formatBDT(data?.preSold?.amount ?? 0)}
                hint={preSoldNote(data?.preSold)}
              />
              <StatTile
                variant="accent"
                label="Net profit today"
                value={formatBDT(today?.netProfit ?? 0)}
                hint={netProfitNote(today)}
              />
            </div>

            <LiveStations
              stations={stations.data}
              receivedAt={stations.dataUpdatedAt}
              loading={stations.isPending}
              failed={stations.isError}
            />

            <div className="h-0.5 bg-divider" />

            <div className="grid grid-cols-[2fr_1fr] gap-5">
              <section className="flex flex-col gap-2.5">
                <h2 className="type-label opacity-55">Revenue, last 30 days</h2>
                <BarChart
                  label="Revenue by day, last 30 days"
                  data={trendBars(data?.revenue30Days?.days)}
                  empty="No sessions yet today"
                />
                <p className="text-[11px] opacity-50">{trendSummary(data?.revenue30Days)}</p>
              </section>

              <section className="flex flex-col gap-2.5">
                <h2 className="type-label opacity-55">By day of week</h2>
                {hasWeekdayData(data?.byDayOfWeek) ? (
                  <div data-testid="weekday-chart" className="flex flex-col gap-2">
                    {weekdayRows(data?.byDayOfWeek).map((row) => (
                      <ProgressBar
                        key={row.key}
                        label={<span className="w-9 shrink-0">{row.label}</span>}
                        value={row.average}
                        max={weekdayPeak(data?.byDayOfWeek)}
                        valueLabel={<span className="w-16">{formatBDT(row.average)}</span>}
                      />
                    ))}
                  </div>
                ) : (
                  <p
                    data-testid="weekday-empty"
                    className="border-2 border-divider p-4 text-[13px] opacity-60"
                  >
                    {NOT_ENOUGH_DATA}
                  </p>
                )}
              </section>
            </div>

            <div className="h-0.5 bg-divider" />

            <div className="grid grid-cols-2 gap-5">
              <StockWatchlist watchlist={data?.stockWatchlist} />
              <RecentCloses closes={data?.recentCloses} />
            </div>
          </>
        )}
      </div>

      <AlertsRail />
    </div>
  );
}

/* ------------------------------------------------------ the stale banner */

/**
 * design.md §1, S2 error row: "Stale-data banner + last-sync time".
 *
 * Two different clocks and both matter: `asOf` is when the figures on screen
 * were true, `lastSyncedAt` is when the cloud last heard from the venue. An
 * owner reading this off-site is usually asking the second question.
 */
function StaleBanner({
  asOf,
  lastSyncedAt,
}: {
  asOf: string | undefined;
  lastSyncedAt: string | null;
}) {
  return (
    <p
      role="status"
      data-testid="overview-stale"
      className="flex items-center gap-2 border-2 border-divider px-3 py-2 text-body"
    >
      <WifiOff aria-hidden="true" className="size-4 shrink-0" strokeWidth={2} />
      <span>
        These figures could not be refreshed
        {asOf ? ` — they are as of ${formatVenueDateTime(asOf)}` : ''}.
        {lastSyncedAt ? ` Last synced ${formatVenueTime(lastSyncedAt)}.` : ''}
      </span>
    </p>
  );
}

/* ------------------------------------------------------ the live cards */

/**
 * The horizontally scrolling live-station strip — click through to the Floor.
 *
 * These are the Floor's own rows (`GET /stations`), rendered small: name, what
 * it is, the clock ticking from the server reading, and who is on it. The full
 * StationCard belongs to S3 where it is the thing being operated; here it is a
 * link, so it stays a link.
 */
function LiveStations({
  stations,
  receivedAt,
  loading,
  failed,
}: {
  stations: Station[] | undefined;
  receivedAt: number;
  loading: boolean;
  failed: boolean;
}) {
  const rows = stations ?? [];

  return (
    <section className="flex flex-col gap-2.5">
      <h2 className="type-label opacity-55">Live stations</h2>

      {loading ? (
        <div data-testid="live-stations-skeleton" aria-busy="true" className="flex gap-3">
          {[0, 1, 2, 3].map((card) => (
            <div key={card} className="h-[104px] w-[236px] flex-none border-2 border-divider" />
          ))}
        </div>
      ) : failed ? (
        <p data-testid="live-stations-error" className="text-[13px] opacity-60">
          The floor could not be read — open Floor to see the consoles.
        </p>
      ) : rows.length === 0 ? (
        <p
          data-testid="live-stations-empty"
          className="border-2 border-divider p-4 text-[13px] opacity-60"
        >
          No sessions yet today — add a console in Setup, or start one from Floor.
        </p>
      ) : (
        <div className="flex gap-3 overflow-x-auto pb-1.5">
          {rows.map((station) => (
            <LiveStationCard key={station.id} station={station} receivedAt={receivedAt} />
          ))}
        </div>
      )}
    </section>
  );
}

function LiveStationCard({ station, receivedAt }: { station: Station; receivedAt: number }) {
  const variant = stationVariant(station);
  const snapshot = stationClockSnapshot(station, receivedAt || Date.now());

  return (
    <Link
      href="/floor"
      data-testid="overview-station-card"
      data-station-id={station.id}
      data-variant={variant}
      className="flex w-[236px] flex-none flex-col gap-1 border-2 border-divider bg-card p-3.5 hover:border-text focus-visible:outline-2 focus-visible:outline-accent focus-visible:outline-offset-2"
    >
      <span className="flex items-baseline gap-2">
        <span className="font-heading text-[17px] leading-none font-extrabold">{station.name}</span>
        <span className="type-label opacity-55">{consoleLabel(station.consoleType)}</span>
        <Tag variant={variant === 'active' ? 'accent' : 'neutral'} className="ml-auto">
          {STATUS_LABELS[variant] ?? variant}
        </Tag>
      </span>
      <CountdownClock variant="match" snapshot={snapshot} />
      <span className="flex justify-between text-[12px] opacity-65">
        <span>{whoIsOn(station)}</span>
        <span className="tabular">{blocksNote(station)}</span>
      </span>
    </Link>
  );
}

const STATUS_LABELS: Record<string, string> = {
  free: 'Free',
  open: 'Open',
  active: 'Playing',
  paused: 'Paused',
  locked: 'Time up',
  reserved: 'Match',
  booked: 'Booked',
  maintenance: 'Service',
};

function whoIsOn(station: Station): string {
  if (station.match) return station.match.tournamentName ?? 'Tournament match';
  if (station.arrival) return station.arrival.name ?? 'Checked-in arrival';
  if (station.session) return station.session.memberId ? 'Member' : 'Walk-in';
  return 'No session';
}

function blocksNote(station: Station): string {
  const blocks = station.session?.blocks ?? station.arrival?.blocks ?? 0;
  if (blocks === 0) return '';
  return `${blocks * 30} min`;
}

/* ------------------------------------------------ watchlist & closes */

function StockWatchlist({ watchlist }: { watchlist: readonly StockWatch[] | undefined }) {
  const rows = stockWatchRows(watchlist);

  return (
    <section className="flex flex-col gap-2.5">
      <h2 className="type-label opacity-55">Stock watchlist</h2>
      {rows.length === 0 ? (
        <p
          data-testid="watchlist-empty"
          className="border-2 border-divider p-4 text-[13px] opacity-60"
        >
          Nothing is under its reorder point.
        </p>
      ) : (
        <div className="flex flex-col gap-3">
          {rows.map((row) => (
            <div key={row.key} data-testid="watchlist-row" className="flex flex-col gap-1">
              <div className="flex justify-between text-[13px]">
                <span className="font-heading font-extrabold">{row.name}</span>
                <span className="opacity-60">{row.note}</span>
              </div>
              <ProgressBar
                value={row.stock}
                max={Math.max(row.reorderAt, row.stock, 1)}
                variant={row.stock === 0 ? 'alt' : 'accent'}
              />
            </div>
          ))}
        </div>
      )}
    </section>
  );
}

/**
 * Staff & recent shift closes. The prototype heads this with whoever is on
 * shift now; that is `GET /shifts/current` — a different read, owned by S7 —
 * so this lists the closes the overview actually carries, and each row names
 * the till that closed it.
 */
function RecentCloses({ closes }: { closes: readonly ShiftClose[] | undefined }) {
  const rows = closes ?? [];

  return (
    <section className="flex flex-col gap-2.5">
      <h2 className="type-label opacity-55">Staff &amp; recent shift closes</h2>
      {rows.length === 0 ? (
        <p
          data-testid="closes-empty"
          className="border-2 border-divider p-4 text-[13px] opacity-60"
        >
          No shift has been closed yet.
        </p>
      ) : (
        <div className="flex flex-col">
          {rows.map((close) => {
            const note = discrepancyNote(close);
            const short = (close.discrepancy ?? 0) !== 0;
            return (
              <div
                key={close.shiftId}
                data-testid="shift-close-row"
                className="flex items-center justify-between gap-3 border-b border-divider py-2.5"
              >
                <div>
                  <p className="font-heading text-[14px] font-extrabold">
                    Shift #{close.shiftId} · {close.terminal ?? 'counter'}
                  </p>
                  <p className="text-[11px] opacity-55">
                    {close.closedAt ? formatVenueDateTime(close.closedAt) : '—'}
                  </p>
                </div>
                <div className="text-right">
                  <p className="text-[14px] tabular">{formatBDT(close.takings ?? 0)}</p>
                  <p
                    className={`text-[11px] ${short ? 'text-accent-strong' : 'opacity-55'}`}
                  >
                    {note ?? 'Not counted'}
                  </p>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </section>
  );
}

/* -------------------------------------------------------------- helpers */

function weekdayPeak(weekdays: readonly Weekday[] | undefined): number {
  return Math.max(1, ...(weekdays ?? []).map((weekday) => weekday.average ?? 0));
}

/** The loading state, shaped like the grid it becomes (design.md §1). */
function TilesSkeleton() {
  return (
    <div data-testid="overview-skeleton" aria-busy="true" className="flex flex-col gap-5">
      <div className="grid grid-cols-5 border-2 border-divider divide-x-2 divide-divider">
        {[0, 1, 2, 3, 4].map((tile) => (
          <div key={tile} className="flex flex-col gap-2 p-4">
            <div className="h-2.5 w-20 bg-track" />
            <div className="h-8 w-24 bg-track" />
            <div className="h-3 w-28 bg-track" />
          </div>
        ))}
      </div>
      <div className="flex gap-3">
        {[0, 1, 2, 3].map((card) => (
          <div key={card} className="h-[104px] w-[236px] flex-none border-2 border-divider" />
        ))}
      </div>
      <div className="h-[150px] border-2 border-divider" />
    </div>
  );
}
