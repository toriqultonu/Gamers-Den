'use client';

/**
 * The two right rails of S12 (docs/tournaments.md §8).
 *
 * **Manager+** gets the selected event's controls — station-block chips,
 * cancel, the finance panel — and the arrange form under them. **Cashier** gets
 * read-only guidance and one button that deep-links the POS in counter mode
 * with the Tournament category open, because selling an entry is the cashier's
 * half of this screen (§1: "Sell entry at POS ✓ / create-edit-cancel ✗").
 *
 * Which rail renders is decided by role, and the API enforces the same matrix
 * whatever renders (§1, three layers: nav → route → 403). Nothing here is a
 * security boundary; it is what the counter can usefully press.
 */

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { AlertTriangle } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { ChipSelect } from '@/components/ui/chip-select';
import { FieldInput } from '@/components/ui/field-input';
import { FinancePanel } from './finance-panel';
import { errorNotice } from '@/lib/api';
import { parseAmount } from '@/lib/money';
import { instantFromVenueLocal, serverNow, venueLocalInput } from '@/lib/time';
import { useAppStore } from '@/features/pos/bill-store';
import {
  useCancelTournament,
  useCreateTournament,
  useSetStationBlocks,
} from '@/features/tournaments/mutations';
import {
  CADENCE_LABELS,
  TOURNAMENT_CADENCES,
  TOURNAMENT_CAPS,
  createTournamentSchema,
  type Tournament,
  type TournamentCadence,
  type TournamentFinance,
} from '@/features/tournaments/schemas';
import type { Station } from '@/features/sessions/queries';

/* ------------------------------------------------------------ cashier rail */

/**
 * The cashier's rail: what they may do, and the way to the one thing that
 * matters — the sale. The deep link puts the POS in counter mode on the
 * Tournament category (prototype `goPosCounter`).
 */
export function CashierRail() {
  const router = useRouter();
  const store = useAppStore.getState;

  return (
    <aside
      data-testid="tournament-cashier-rail"
      className="flex w-[300px] flex-none flex-col gap-3.5 overflow-auto border-l-2 border-divider bg-surface p-5"
    >
      <p className="type-label opacity-55">Cashier view</p>
      <p className="text-body opacity-80">
        Only a manager or the admin can arrange, edit or cancel tournaments and see event finances.
        You run the matches: start pending matches from the match board — the system assigns a free
        allocated console — and record the winner from the bracket when a match ends; they advance
        automatically.
      </p>
      <div className="h-0.5 bg-divider" />
      <p className="text-body opacity-80">
        To register a customer: open the point of sale, pick the tournament entry from the
        Tournament category, type the player&rsquo;s name (or attach a member), take the fee, and
        print the ticket — its QR code is their bracket pass. The sale lands in this shift&rsquo;s
        X/Z report automatically, and the bracket draws itself when the last slot sells.
      </p>
      <Button
        variant="block"
        size="lg"
        data-testid="sell-entry-at-pos"
        onClick={() => {
          store().setPosMode('counter');
          store().setCategory('TOURNAMENT');
          router.push('/pos');
        }}
      >
        Sell an entry at the POS
      </Button>
    </aside>
  );
}

/* ------------------------------------------------------------ manager rail */

export type ManagerRailProps = {
  tournament: Tournament | undefined;
  /** The consoles currently held for the event (`TournamentDetail.stationIds`). */
  stationIds: number[];
  stations: Station[] | undefined;
  finance: TournamentFinance | undefined;
  financeLoading?: boolean;
  financeError?: unknown;
};

export function ManagerRail({
  tournament,
  stationIds,
  stations,
  finance,
  financeLoading,
  financeError,
}: ManagerRailProps) {
  const [notice, setNotice] = useState<string | null>(null);
  const blocks = useSetStationBlocks();
  const cancel = useCancelTournament();

  const rows = stations ?? [];
  const tournamentId = tournament?.id ?? null;
  const finished = tournament?.status === 'DONE' || tournament?.status === 'CANCELLED';

  return (
    <aside
      data-testid="tournament-manager-rail"
      className="flex w-[356px] flex-none flex-col gap-3 overflow-auto border-l-2 border-divider bg-surface p-5"
    >
      {notice ? (
        <p
          role="alert"
          data-testid="manager-rail-notice"
          className="flex items-start gap-2 border-2 border-accent px-3 py-2 text-body text-accent-strong"
        >
          <AlertTriangle aria-hidden="true" className="mt-0.5 size-4 shrink-0" strokeWidth={2} />
          {notice}
        </p>
      ) : null}

      {tournament && typeof tournamentId === 'number' ? (
        <>
          <p className="type-label opacity-55">Selected tournament</p>
          <p className="font-heading text-[17px] leading-tight font-extrabold">{tournament.name}</p>

          <div className="flex flex-col gap-1.5">
            <span className="text-[12px] opacity-70">Blocked stations (no walk-in sessions)</span>
            <ChipSelect
              multiple
              label="Blocked stations"
              options={rows.map((station) => ({
                value: String(station.id),
                label: station.name ?? 'Console',
              }))}
              value={stationIds.map(String)}
              onChange={(next) => {
                setNotice(null);
                blocks.mutate(
                  { tournamentId, stationIds: next.map(Number) },
                  {
                    onError: (error) =>
                      setNotice(errorNotice(error, 'The console allocation was not saved.')),
                  },
                );
              }}
            />
          </div>

          <Button
            variant="block"
            data-testid="cancel-tournament"
            disabled={finished || cancel.isPending}
            loading={cancel.isPending}
            onClick={() => {
              setNotice(null);
              cancel.mutate(
                { tournamentId },
                {
                  onError: (error) =>
                    setNotice(errorNotice(error, 'The tournament was not called off.')),
                },
              );
            }}
          >
            Cancel tournament &amp; refund entries
          </Button>

          <div className="h-0.5 bg-divider" />

          <FinancePanel finance={finance} loading={financeLoading} error={financeError} />
        </>
      ) : (
        <p className="text-body opacity-60">
          No tournament selected — arrange one below and it becomes the selected event.
        </p>
      )}

      <div className="h-0.5 bg-divider" />
      <ArrangeForm stations={rows} />
    </aside>
  );
}

/* ------------------------------------------------------------ arrange form */

/** The prototype's default match length; the DDL's default too. */
export const DEFAULT_MATCH_MINUTES = 20;

/** Two hours out, rounded to the half hour — a counter's guess at "tonight". */
export function defaultSchedule(at: number = serverNow()): number {
  const half = 30 * 60_000;
  return Math.ceil((at + 2 * 60 * 60_000) / half) * half;
}

export function ArrangeForm({ stations }: { stations: Station[] }) {
  const create = useCreateTournament();

  const [name, setName] = useState('');
  const [game, setGame] = useState('');
  const [when, setWhen] = useState(() => venueLocalInput(defaultSchedule()));
  const [cadence, setCadence] = useState<TournamentCadence>('WEEKLY');
  const [cap, setCap] = useState<number>(8);
  const [fee, setFee] = useState('200');
  const [prize, setPrize] = useState('1200');
  const [minutes, setMinutes] = useState(String(DEFAULT_MATCH_MINUTES));
  const [blocked, setBlocked] = useState<string[]>([]);
  const [submitted, setSubmitted] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  const parsed = createTournamentSchema.safeParse({
    name,
    game,
    cadence,
    scheduledAt: instantFromVenueLocal(when) ?? undefined,
    maxPlayers: cap,
    entryFee: parseAmount(fee) ?? undefined,
    prizePool: parseAmount(prize) ?? undefined,
    matchDurationMin: parseAmount(minutes) ?? undefined,
    stationIds: blocked.map(Number),
  });

  // Field errors appear only once the operator has tried to create: a form that
  // scolds while it is still being filled in is noise.
  const issues = parsed.success ? [] : parsed.error.issues;
  const errorFor = (field: string) =>
    submitted && !parsed.success
      ? issues.find((issue) => issue.path[0] === field)?.message
      : undefined;

  const submit = () => {
    setSubmitted(true);
    setNotice(null);
    if (!parsed.success || create.isPending) return;

    create.mutate(parsed.data, {
      onSuccess: () => {
        setName('');
        setGame('');
        setBlocked([]);
        setSubmitted(false);
      },
      // DUPLICATE_NAME and the validation 409s leave nothing written, and
      // nothing typed is cleared (§4.4).
      onError: (error) => setNotice(errorNotice(error, 'The tournament was not created.')),
    });
  };

  return (
    <div data-testid="arrange-form" className="flex flex-col gap-3">
      <p className="type-label opacity-55">Arrange a tournament</p>

      {notice ? (
        <p
          role="alert"
          data-testid="arrange-notice"
          className="border-2 border-accent px-3 py-2 text-body text-accent-strong"
        >
          {notice}
        </p>
      ) : null}

      <FieldInput
        label="Name"
        value={name}
        autoComplete="off"
        placeholder="e.g. FIFA Cup #32"
        error={errorFor('name')}
        disabled={create.isPending}
        onChange={(event) => setName(event.target.value)}
      />

      <div className="grid grid-cols-2 gap-2">
        <FieldInput
          label="Game"
          value={game}
          autoComplete="off"
          placeholder="FIFA 25"
          error={errorFor('game')}
          disabled={create.isPending}
          onChange={(event) => setGame(event.target.value)}
        />
        <FieldInput
          label="When"
          type="datetime-local"
          value={when}
          data-testid="tournament-when"
          error={errorFor('scheduledAt') ? 'Pick a date and time.' : undefined}
          disabled={create.isPending}
          onChange={(event) => setWhen(event.target.value)}
        />
      </div>

      <div className="flex flex-col gap-1.5">
        <span className="text-[12px] opacity-70">Cadence</span>
        <ChipSelect
          label="Cadence"
          options={TOURNAMENT_CADENCES.map((value) => ({
            value,
            label: CADENCE_LABELS[value],
          }))}
          value={cadence}
          onChange={(value) => setCadence(value)}
        />
      </div>

      <div className="flex flex-col gap-1.5">
        <span className="text-[12px] opacity-70">
          Player cap — a bracket needs a power of two
        </span>
        <ChipSelect
          label="Player cap"
          options={TOURNAMENT_CAPS.map((value) => ({ value: String(value), label: String(value) }))}
          value={String(cap)}
          onChange={(value) => setCap(Number(value))}
        />
        <span className="text-[11px] opacity-55">
          {`${cap} players · ${cap - 1} matches, no byes.`}
        </span>
      </div>

      <div className="grid grid-cols-3 gap-2">
        <FieldInput
          label="Entry fee"
          inputMode="numeric"
          value={fee}
          suffix="৳"
          error={errorFor('entryFee')}
          disabled={create.isPending}
          onChange={(event) => setFee(event.target.value)}
        />
        <FieldInput
          label="Prize pool"
          inputMode="numeric"
          value={prize}
          suffix="৳"
          error={errorFor('prizePool')}
          disabled={create.isPending}
          onChange={(event) => setPrize(event.target.value)}
        />
        <FieldInput
          label="Match"
          inputMode="numeric"
          value={minutes}
          suffix="min"
          error={errorFor('matchDurationMin')}
          disabled={create.isPending}
          onChange={(event) => setMinutes(event.target.value)}
        />
      </div>

      <div className="flex flex-col gap-1.5">
        <span className="text-[12px] opacity-70">Block stations for the event</span>
        <ChipSelect
          multiple
          label="Block stations"
          options={stations.map((station) => ({
            value: String(station.id),
            label: station.name ?? 'Console',
          }))}
          value={blocked}
          onChange={setBlocked}
        />
      </div>

      <Button
        variant="block"
        size="lg"
        data-testid="create-tournament"
        loading={create.isPending}
        disabled={create.isPending}
        onClick={submit}
      >
        Create tournament
      </Button>
    </div>
  );
}
