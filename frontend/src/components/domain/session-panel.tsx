'use client';

/**
 * SessionPanel — docs/design.md §2: variants station · reserved · seat-prompt ·
 * empty; states running · paused · open · locked · match.
 *
 * The right rail of S3. What it can do to a console depends entirely on what
 * the console is:
 *
 * - **station** — a live session: the 50px clock, ±30-minute blocks, the
 *   clock's start/pause/resume, the running bill, the link into the POS, and
 *   End, which is refused while the **net** balance is unsettled. Net, not
 *   gross: a seated booking arrives with its blocks already paid, so it ends
 *   without anyone taking a payment (docs/bookings.md §2).
 * - **reserved** — a console a tournament holds. With a started match it shows
 *   the match clock and its players and reads "match over" past zero; either
 *   way it refuses a walk-in start, which is design.md §1's S3 row and the
 *   server's `STATION_RESERVED` saying the same thing.
 * - **seat-prompt** — a free console with prepaid time waiting for it: the
 *   arrival its booking checked in ("Seat #04 · Rahim · 2 h prepaid"), then any
 *   waiting play-queue token of the same type. Seating loads the prepaid blocks
 *   as **already paid**, and the clock starts when they sit down.
 * - **empty** — free, with nothing waiting: start a walk-in session.
 *
 * Only ±30 min is optimistic. Start, seat, clock and end all wait for the
 * server, because each of them is either money or a fact the floor acts on
 * (frontend/ARCHITECTURE.md §5.3).
 */

import Link from 'next/link';
import { cn } from '@/components/ui/cn';
import { Button } from '@/components/ui/button';
import { formatToken } from '@/components/ui/token-badge';
import { formatBlocks } from '@/components/ui/time-stepper';
import { CountdownClock } from './countdown-clock';
import { consoleLabel } from './station-card';
import { formatBDT } from '@/lib/money';
import { serverOffsetMs } from '@/lib/time';
import { clockSnapshot, hasBalance, stationClockSnapshot, stationVariant } from '@/features/sessions/schemas';
import type { Session, Station } from '@/features/sessions/schemas';
import type { Bill } from '@/features/sessions/queries';
import type { QueueEntry } from '@/features/queue/queries';
import type { ClockAction } from '@/features/sessions/mutations';

export const SESSION_PANEL_VARIANTS = ['station', 'reserved', 'seat-prompt', 'empty'] as const;
export type SessionPanelVariant = (typeof SESSION_PANEL_VARIANTS)[number];

export const SESSION_PANEL_STATES = ['running', 'paused', 'open', 'locked', 'match'] as const;
export type SessionPanelState = (typeof SESSION_PANEL_STATES)[number];

/** One seatable thing: a checked-in arrival, or a waiting play token. */
export type SeatOffer = {
  queueEntryId: number;
  token: number;
  name: string;
  blocks: number;
  source: 'BOOKING' | 'PLAY_TICKET';
};

export type SessionPanelProps = {
  station: Station | null;
  /** `GET /sessions/{id}` for the console's live session, when there is one. */
  session?: Session;
  /** `GET /sessions/{id}/bill` — the running bill down the panel's side. */
  bill?: Bill;
  /** Waiting tokens this console can take, in token order. */
  waiting?: QueueEntry[];
  /** When `['stations']` last landed — the card clock's `asOf`. */
  receivedAt?: number;
  /** The one notice line: a rolled-back block, a refused seat, a blocked end. */
  notice?: string | null;
  /** Which action is in flight, so exactly one control shows the spinner. */
  busy?: 'start' | 'blocks' | 'clock' | 'end' | 'seat' | null;
  onStart?: (station: Station) => void;
  onBlocks?: (delta: 1 | -1) => void;
  onClock?: (action: ClockAction) => void;
  onEnd?: () => void;
  onSeat?: (offer: SeatOffer) => void;
  /** The floor could not be read, or the role may not write — controls off. */
  disabled?: boolean;
  className?: string;
};

/** Which of the four the panel is, from the console alone. */
export function sessionPanelVariant(
  station: Station | null,
  offers: readonly SeatOffer[] = [],
): SessionPanelVariant {
  if (!station) return 'empty';
  const variant = stationVariant(station);
  if (variant === 'reserved') return 'reserved';
  if (station.session) return 'station';
  if (offers.length > 0) return 'seat-prompt';
  return 'empty';
}

/** The five states design.md gives the panel. */
export function sessionPanelState(
  station: Station | null,
  session: Session | undefined,
): SessionPanelState | null {
  if (!station) return null;
  if (stationVariant(station) === 'reserved') return station.match ? 'match' : null;
  const state = session?.state ?? station.session?.state;
  switch (state) {
    case 'RUNNING':
      return 'running';
    case 'PAUSED':
      return 'paused';
    case 'OPEN':
      return 'open';
    case 'LOCKED':
      return 'locked';
    default:
      return null;
  }
}

/**
 * Everything prepaid that is waiting for this console.
 *
 * The console's own checked-in arrival comes first — it was sold this seat —
 * then any waiting play-queue token of the same type, because a token may be
 * seated on any free console of its type (docs/bookings.md §7).
 */
export function seatOffers(
  station: Station | null,
  waiting: readonly QueueEntry[] = [],
): SeatOffer[] {
  if (!station) return [];
  if (station.session || stationVariant(station) === 'reserved') return [];
  if (stationVariant(station) === 'maintenance') return [];

  const offers: SeatOffer[] = [];
  const arrival = station.arrival;
  if (arrival && typeof arrival.queueEntryId === 'number') {
    offers.push({
      queueEntryId: arrival.queueEntryId,
      token: arrival.token ?? 0,
      name: arrival.name ?? 'Booking',
      blocks: arrival.blocks ?? 1,
      source: 'BOOKING',
    });
  }

  for (const entry of waiting) {
    if (typeof entry.id !== 'number') continue;
    if (entry.status !== 'WAITING') continue;
    if (entry.consoleType !== station.consoleType) continue;
    if (offers.some((offer) => offer.queueEntryId === entry.id)) continue;
    offers.push({
      queueEntryId: entry.id,
      token: entry.tokenNo ?? 0,
      name: entry.playerName || 'Walk-in guest',
      blocks: entry.blocks ?? 1,
      source: 'PLAY_TICKET',
    });
  }

  return offers;
}

/** "Seat #04 · Rahim · 2 h prepaid" — design.md's S3 seat prompt, verbatim. */
export function seatLabel(offer: SeatOffer): string {
  return `Seat ${formatToken(offer.token)} · ${offer.name} · ${formatBlocks(offer.blocks)} prepaid`;
}

export function SessionPanel({
  station,
  session,
  bill,
  waiting = [],
  receivedAt,
  notice = null,
  busy = null,
  onStart,
  onBlocks,
  onClock,
  onEnd,
  onSeat,
  disabled = false,
  className,
}: SessionPanelProps) {
  const offers = seatOffers(station, waiting);
  const variant = sessionPanelVariant(station, offers);
  const state = sessionPanelState(station, session);

  if (!station) {
    return (
      <aside
        data-testid="session-panel"
        data-variant="empty"
        className={cn(panelShell, className)}
      >
        <p className="text-body opacity-60">Select a console to see its session.</p>
      </aside>
    );
  }

  // `Session` dates its own reading with `serverTime`; the station summary does
  // not, so its fallback is when the list landed, converted to server time.
  const landedAt = receivedAt ?? Date.now();
  const snapshot = session
    ? clockSnapshot(session, landedAt + serverOffsetMs())
    : stationClockSnapshot(station, landedAt);

  const blocks = session?.blocks ?? station.session?.blocks ?? 0;
  const paidBlocks = session?.paidBlocks ?? station.session?.paidBlocks ?? 0;
  const outstanding = session?.netOutstanding ?? bill?.netTotal ?? 0;
  const endBlocked = session ? hasBalance(session) : outstanding > 0;
  const clockState = session?.state ?? station.session?.state;
  const running = clockState === 'RUNNING';
  // **Paused, not "has a startedAt".** `sessions.started_at` defaults to `now()`
  // when the row is written (V001), so it says the session was opened, not that
  // the clock has ever run — reading it as "started" offers Resume on a
  // brand-new session, and RESUME is legal only from PAUSED (`ClockAction`), so
  // the server answers 409 and the walk-in never gets a clock. The state is the
  // only honest source: OPEN and LOCKED start, PAUSED resumes, RUNNING pauses.
  const paused = clockState === 'PAUSED';

  return (
    <aside
      data-testid="session-panel"
      data-variant={variant}
      data-state={state ?? undefined}
      className={cn(panelShell, className)}
    >
      <header>
        <p className="type-label text-accent-strong">{consoleLabel(station.consoleType)}</p>
        <h2 className="font-heading text-[36px] leading-none font-extrabold tracking-[-0.04em]">
          {station.name}
        </h2>
      </header>

      <hr className="rule" />

      <div className="flex flex-col gap-1">
        <p className="type-label opacity-60">{clockKicker(station, variant, state)}</p>
        <CountdownClock variant="panel" snapshot={snapshot} />
        <p className="text-[12px] opacity-75">{clockMeta(station, variant, blocks)}</p>
      </div>

      {notice ? (
        <p role="alert" data-testid="panel-notice" className="border-2 border-accent px-3 py-2 text-[12px] text-accent-strong">
          {notice}
        </p>
      ) : null}

      {variant === 'station' ? (
        <>
          <div className="flex flex-col gap-2">
            <div className="grid grid-cols-2 gap-2">
              <Button
                variant="secondary"
                disabled={disabled || blocks <= paidBlocks}
                loading={busy === 'blocks'}
                onClick={() => onBlocks?.(-1)}
              >
                −30 min block
              </Button>
              <Button
                variant="secondary"
                disabled={disabled}
                loading={busy === 'blocks'}
                onClick={() => onBlocks?.(1)}
              >
                +30 min block
              </Button>
            </div>
            <Button
              variant="secondary"
              className="w-full border-text bg-text text-bg"
              disabled={disabled || blocks === 0}
              loading={busy === 'clock'}
              onClick={() => onClock?.(running ? 'PAUSE' : paused ? 'RESUME' : 'START')}
            >
              {running ? 'Pause the clock' : paused ? 'Resume the clock' : 'Start the clock'}
            </Button>
          </div>

          <hr className="rule" />

          <RunningBill bill={bill} blocks={blocks} />
        </>
      ) : null}

      {variant === 'seat-prompt' ? (
        <section className="flex flex-col gap-2">
          <h3 className="type-label text-accent-strong">
            {offers[0]?.source === 'BOOKING' ? 'Pre-booking — arrival' : 'Play queue — seat next'}
          </h3>
          {offers.map((offer) => (
            <Button
              key={`${offer.source}-${offer.queueEntryId}`}
              variant="secondary"
              className="w-full"
              data-testid="seat-offer"
              data-queue-entry-id={offer.queueEntryId}
              disabled={disabled}
              loading={busy === 'seat'}
              onClick={() => onSeat?.(offer)}
            >
              {seatLabel(offer)}
            </Button>
          ))}
          <p className="text-[11px] opacity-55">
            Seating loads the prepaid time as already paid — the clock starts when they sit down.
          </p>
        </section>
      ) : null}

      {variant === 'reserved' ? (
        <section className="flex flex-col gap-2">
          <h3 className="type-label text-accent-strong">Tournament</h3>
          <p className="text-body opacity-75">
            {station.match?.playerA && station.match?.playerB
              ? `${station.match.playerA} vs ${station.match.playerB}`
              : 'This console is blocked for a tournament.'}
          </p>
          <p data-testid="reserved-note" className="text-[12px] opacity-60">
            Reserved consoles refuse a walk-in session. Extra match time is added from the
            Tournaments screen.
          </p>
        </section>
      ) : null}

      <div className="mt-auto flex flex-col gap-2">
        {variant === 'station' ? (
          <>
            <Link
              href="/pos"
              className="w-full border-2 border-text bg-text px-4 py-2 text-center font-heading text-body font-extrabold text-bg"
            >
              Add food &amp; drinks
            </Link>
            <Link
              href="/pos"
              data-testid="bill-link"
              className="w-full bg-accent px-4 py-3 text-center font-heading text-[15px] font-extrabold text-on-accent"
            >
              Bill &amp; take payment
            </Link>
            <Button
              variant="secondary"
              className="w-full border-text bg-text text-bg"
              data-testid="end-session"
              disabled={disabled || endBlocked}
              loading={busy === 'end'}
              onClick={() => onEnd?.()}
            >
              {endBlocked
                ? `${formatBDT(outstanding)} due — settle before ending`
                : 'End session & free the station'}
            </Button>
            {endBlocked ? (
              <p data-testid="end-blocked-note" className="text-[11px] opacity-60">
                Settle the outstanding balance before ending this session.
              </p>
            ) : null}
          </>
        ) : null}

        {variant === 'reserved' ? (
          <Button variant="secondary" className="w-full" disabled>
            {`Reserved · ${station.match?.tournamentName ?? 'tournament'}`}
          </Button>
        ) : null}

        {variant === 'seat-prompt' || variant === 'empty' ? (
          <Button
            variant="primary"
            className="w-full"
            data-testid="start-session"
            disabled={disabled || stationVariant(station) === 'maintenance'}
            loading={busy === 'start'}
            onClick={() => onStart?.(station)}
          >
            {stationVariant(station) === 'maintenance'
              ? 'Out of service'
              : 'Start a walk-in session'}
          </Button>
        ) : null}

        {variant === 'empty' && stationVariant(station) !== 'maintenance' ? (
          <Link href="/pos" className="text-center text-[12px] text-accent-strong underline underline-offset-4">
            Sell a prepaid play ticket (token) at the POS
          </Link>
        ) : null}
      </div>
    </aside>
  );
}

const panelShell =
  'flex w-[356px] flex-none flex-col gap-3.5 overflow-auto border-l-2 border-divider bg-surface p-5';

/** The kicker over the digits — the prototype's `sel.timeLabel`. */
function clockKicker(
  station: Station,
  variant: SessionPanelVariant,
  state: SessionPanelState | null,
): string {
  if (variant === 'reserved') {
    if (!station.match) return 'Reserved for a tournament';
    return station.match.timeUp ? 'Match over — record the winner' : 'Match time remaining';
  }
  if (variant !== 'station') return 'No active session';
  switch (state) {
    case 'open':
      return 'Open — add a 30 min block';
    case 'running':
      return 'Time remaining';
    case 'paused':
      return 'Paused · time remaining';
    case 'locked':
      return 'Time up — add a block to carry on';
    default:
      return 'Time bought — not started';
  }
}

/** The line under the digits — what was bought, or what is waiting. */
function clockMeta(station: Station, variant: SessionPanelVariant, blocks: number): string {
  if (variant === 'reserved') {
    return station.match?.tournamentName ?? 'Blocked by the manager';
  }
  if (variant === 'seat-prompt') {
    return 'Prepaid time is waiting — seat it below.';
  }
  if (variant === 'empty') {
    return stationVariant(station) === 'maintenance'
      ? 'Out of service — take it off maintenance in Setup.'
      : 'Start a session to begin billing.';
  }
  if (blocks === 0) return 'No time bought yet · nothing billed.';
  return `${blocks} × 30 min bought`;
}

/** The running bill, straight off `GET /sessions/{id}/bill`. */
function RunningBill({ bill, blocks }: { bill: Bill | undefined; blocks: number }) {
  const gaming = bill?.gamingDue ?? 0;
  const fnb = bill?.fnbDue ?? 0;
  const tournament = bill?.tournamentDue ?? 0;
  const credit = bill?.prepaidCredit ?? 0;
  const total = bill?.netTotal ?? 0;

  return (
    <section data-testid="running-bill" className="flex flex-col gap-1.5">
      <h3 className="type-label opacity-55">Running bill</h3>
      <BillRow label={`Gaming · ${blocks} × 30 min`} amount={gaming} />
      <BillRow label="Food & beverage" amount={fnb} />
      {tournament > 0 ? <BillRow label="Tournament entries" amount={tournament} /> : null}
      {credit > 0 ? <BillRow label="Prepaid credit" amount={-credit} /> : null}
      <hr className="rule-hair" />
      <div className="flex justify-between font-heading text-[22px] font-extrabold">
        <span>Total</span>
        <span data-testid="bill-total" className="tabular">
          {formatBDT(total)}
        </span>
      </div>
    </section>
  );
}

function BillRow({ label, amount }: { label: string; amount: number }) {
  return (
    <div className="flex justify-between text-[13px]">
      <span>{label}</span>
      <span className="tabular">{formatBDT(amount)}</span>
    </div>
  );
}
