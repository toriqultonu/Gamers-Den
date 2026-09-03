'use client';

/**
 * AlertsRail — design.md §2 ("AlertsRail (bell/expanded)") and the S2 row:
 * "collapsible alerts rail (bell + unread badge)".
 *
 * Two faces of one rail:
 *
 *  - **collapsed** — a 64px strip with the bell and the unread badge. The badge
 *    is the count of unread rows in the feed the rail already holds, so an
 *    `alert` event arriving over SSE moves it without a round trip
 *    (`lib/sse.ts` writes straight into `['alerts']`).
 *  - **expanded** — the cards, newest first, unread ones on `color.card` with
 *    an accent rule so the eye finds them in a list that is mostly history.
 *
 * Which face it opens on is the viewport's business, not the store's:
 * design.md §4 has it **starting** collapsed between 1024 and 1279 and open
 * above. So `alertsRailOpen` is `null` until the operator touches it, and only
 * then does the stored choice win over the default.
 *
 * Marking read is a real write and is not optimistic — the rail is a list of
 * things that already happened, and a badge that disagrees with the next event
 * is worse than a frame of latency.
 */

import { Bell, BellOff } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Tag } from '@/components/ui/tag';
import { cn } from '@/components/ui/cn';
import { errorNotice } from '@/lib/api';
import { formatVenueDateTime, formatVenueTime, isVenueToday, venueDate } from '@/lib/time';
import { WIDE_VIEWPORT, useMediaQuery } from '@/lib/use-media-query';
import { useAppStore } from '@/features/pos/bill-store';
import { useAlerts, useMarkAllAlertsRead, useMarkAlertRead } from '@/features/reports/queries';
import { alertKindLabel, badgeLabel, unreadCount, type Alert } from '@/features/reports/schemas';

export function AlertsRail() {
  const stored = useAppStore((state) => state.alertsRailOpen);
  const setOpen = useAppStore((state) => state.setAlertsRailOpen);
  const wide = useMediaQuery(WIDE_VIEWPORT);
  const open = stored ?? wide;

  const alerts = useAlerts();
  const markAll = useMarkAllAlertsRead();
  const markOne = useMarkAlertRead();

  const rows = alerts.data ?? [];
  const unread = unreadCount(rows);

  if (!open) {
    return (
      <aside
        data-testid="alerts-rail"
        data-state="collapsed"
        className="flex w-16 flex-none flex-col items-center gap-2 border-l-2 border-divider bg-surface py-4"
      >
        <button
          type="button"
          onClick={() => setOpen(true)}
          aria-label={unread > 0 ? `Open alerts — ${unread} unread` : 'Open alerts'}
          className="relative grid size-10 place-items-center border-2 border-transparent hover:border-divider focus-visible:outline-2 focus-visible:outline-accent focus-visible:-outline-offset-2"
        >
          <Bell aria-hidden="true" className="size-5" strokeWidth={2} />
          {unread > 0 ? (
            <span
              data-testid="alerts-badge"
              className="absolute -top-1.5 -right-1.5 grid h-[18px] min-w-[18px] place-items-center bg-accent px-1 font-heading text-[11px] font-extrabold text-on-accent"
            >
              {badgeLabel(unread)}
            </span>
          ) : null}
        </button>
        <span className="type-label opacity-50 [writing-mode:vertical-rl]">Alerts</span>
      </aside>
    );
  }

  return (
    <aside
      data-testid="alerts-rail"
      data-state="expanded"
      className="flex w-[356px] flex-none flex-col gap-3.5 overflow-auto border-l-2 border-divider bg-surface p-5"
    >
      <div className="flex items-center gap-2.5">
        <Bell aria-hidden="true" className="size-4.5" strokeWidth={2} />
        <span className="type-label opacity-55">Alerts</span>
        {unread > 0 ? (
          <Tag variant="accent" data-testid="alerts-badge">
            {badgeLabel(unread)} unread
          </Tag>
        ) : null}
        <Button
          variant="ghost"
          size="sm"
          className="ml-auto"
          onClick={() => setOpen(false)}
          aria-label="Close alerts"
        >
          Close
        </Button>
      </div>

      {unread > 0 ? (
        <Button
          variant="secondary"
          size="sm"
          loading={markAll.isPending}
          onClick={() => markAll.mutate()}
        >
          Mark all read
        </Button>
      ) : null}

      {markAll.isError || markOne.isError ? (
        <p
          role="alert"
          data-testid="alerts-write-error"
          className="border-2 border-accent px-3 py-2 text-[12px] text-accent-strong"
        >
          {errorNotice(markAll.error ?? markOne.error, 'That alert could not be marked read.')}
        </p>
      ) : null}

      {alerts.isPending ? (
        <div data-testid="alerts-skeleton" aria-busy="true" className="flex flex-col gap-2">
          {[0, 1, 2].map((card) => (
            <div key={card} className="border-2 border-divider p-3.5">
              <div className="h-3 w-20 bg-track" />
              <div className="mt-2 h-4 w-40 bg-track" />
              <div className="mt-2 h-3 w-48 bg-track" />
            </div>
          ))}
        </div>
      ) : alerts.isError ? (
        <p role="alert" data-testid="alerts-error" className="text-body text-accent-strong">
          {errorNotice(alerts.error, 'The alert feed could not be read.')}
        </p>
      ) : rows.length === 0 ? (
        <p
          data-testid="alerts-empty"
          className="flex items-start gap-2 text-body opacity-60"
        >
          <BellOff aria-hidden="true" className="mt-0.5 size-4 shrink-0" strokeWidth={2} />
          Nothing to report — no discrepancies, failed prints or empty shelves.
        </p>
      ) : (
        rows.map((alert) => (
          <AlertCard
            key={alert.id}
            alert={alert}
            onRead={() => (alert.id ? markOne.mutate(alert.id) : undefined)}
          />
        ))
      )}
    </aside>
  );
}

/**
 * AlertCard — design.md §2. Unread sits on `color.card` behind an accent rule;
 * a read one is a quiet outline, because it is history the operator kept.
 */
function AlertCard({ alert, onRead }: { alert: Alert; onRead: () => void }) {
  const unread = alert.read !== true;
  return (
    <article
      data-testid="alert-card"
      data-alert-type={alert.type}
      data-unread={unread || undefined}
      className={cn(
        'flex flex-col gap-1 border-2 p-3.5',
        unread ? 'border-accent bg-card' : 'border-divider bg-transparent opacity-75',
      )}
    >
      <div className="flex items-baseline gap-2">
        <span className="type-label text-accent-strong">{alertKindLabel(alert.type)}</span>
        <span className="ml-auto text-[11px] opacity-50">{alertTime(alert.createdAt)}</span>
      </div>
      <h3 className="font-heading text-[16px] leading-tight font-extrabold">{alert.title}</h3>
      <p className="text-[12px] opacity-70">{alert.body}</p>
      {unread ? (
        <Button variant="ghost" size="sm" className="self-start px-0" onClick={onRead}>
          Mark read
        </Button>
      ) : null}
    </article>
  );
}

/** `14:05` for today's alerts, `2 Sep, 14:05` once they are older. */
function alertTime(createdAt: string | undefined): string {
  if (!createdAt) return '';
  return isVenueToday(venueDate(createdAt))
    ? formatVenueTime(createdAt)
    : formatVenueDateTime(createdAt);
}
