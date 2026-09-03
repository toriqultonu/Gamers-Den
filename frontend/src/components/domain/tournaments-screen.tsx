'use client';

/**
 * S12 — Tournaments (design.md §1 S12 + §2 component rows,
 * docs/tournaments.md §8).
 *
 * Three columns. The left rail lists the events still selling or being played.
 * The middle is the event itself, and it has two faces decided by one fact —
 * **has the bracket been drawn?** Before the draw it is the registered-player
 * list with its seeds and slots-left note; after it, the "Now on «console»"
 * tiles, the champion banner when the final is in, the bracket columns and the
 * match board. The right rail is the role: Manager+ gets the controls and the
 * finance panel, a cashier gets guidance and the POS deep-link.
 *
 * Two rules this screen exists to keep:
 *
 *  - **Winner recording is never optimistic** (§5.3). The click sends, and the
 *    bracket redraws from the server's answer; a refusal raises the banner
 *    design.md §1 asks for and leaves the bracket exactly as it was.
 *  - **The finance query is not mounted for a cashier** (docs/tournaments.md
 *    §6). Hiding the rail is cosmetic; not asking is what keeps a guaranteed
 *    403 off the wire. The endpoint refuses either way.
 *
 * Every countdown on the screen — tiles, bracket tags, board rows — ticks from
 * the same server `remainingSeconds` (§5.2), so a +5 min extend re-bases all of
 * them together off the next read.
 */

import { useMemo, useState } from 'react';
import { AlertTriangle, Trophy } from 'lucide-react';
import { AccessNotice } from './access-notice';
import { BracketView } from './bracket-view';
import { ChampionBanner } from './champion-banner';
import { LiveMatchTile } from './live-match-tile';
import { MatchBoard } from './match-board';
import { CashierRail, ManagerRail } from './tournament-rails';
import { Button } from '@/components/ui/button';
import { DataTable, type Column } from '@/components/ui/data-table';
import { SegmentedChoice } from '@/components/ui/segmented-choice';
import { Tag } from '@/components/ui/tag';
import { cn } from '@/components/ui/cn';
import { errorNotice, isApiError } from '@/lib/api';
import { formatBDT } from '@/lib/money';
import { formatVenueDateTime } from '@/lib/time';
import type { Role } from '@/lib/nav';
import { useAppStore } from '@/features/pos/bill-store';
import { useStations } from '@/features/sessions/queries';
import {
  useMatchBoard,
  useTournamentDetail,
  useTournamentFinance,
  useTournamentHistory,
  useTournaments,
} from '@/features/tournaments/queries';
import { useGenerateBracket, useRecordWinner } from '@/features/tournaments/mutations';
import {
  CADENCE_LABELS,
  expectedMatchCount,
  liveMatches,
  slotsLabel,
  slotsNote,
  tournamentMeta,
  tournamentStatusLabel,
  tournamentStatusTag,
  type Tournament,
  type TournamentCadence,
  type TournamentEntry,
  type TournamentMatch,
  type TournamentTab,
} from '@/features/tournaments/schemas';

/** Manager and Admin arrange, cancel, block consoles and see the finances (§1). */
export function canManageTournaments(role: Role | null | undefined): boolean {
  return role === 'ADMIN' || role === 'MANAGER';
}

export type TournamentsScreenProps = {
  /**
   * The signed-in role, from the session cookie the middleware just checked.
   * It decides which rail renders and whether the finance query mounts — both
   * cosmetic; the API answers 403 whatever this says (§4.3).
   */
  role: Role | null;
};

export function TournamentsScreen({ role }: TournamentsScreenProps) {
  const canManage = canManageTournaments(role);
  const store = useAppStore.getState;
  const tab = useAppStore((state) => state.tournamentsTab);
  const selectedId = useAppStore((state) => state.selectedTournamentId);

  /** The last winner that would not record — design.md §1, S12's error state. */
  const [notice, setNotice] = useState<string | null>(null);

  const tournaments = useTournaments();
  const history = useTournamentHistory({ enabled: tab === 'history' });
  const stations = useStations();

  const list = tournaments.data ?? [];
  // Nothing picked yet: the first event is the one the counter is running.
  const openId = selectedId ?? list[0]?.id ?? null;

  const detail = useTournamentDetail(tab === 'live' ? openId : null);
  const board = useMatchBoard(tab === 'live' ? openId : null);
  const finance = useTournamentFinance(openId, { enabled: canManage && tab === 'live' });

  const record = useRecordWinner();

  const tournament = detail.data?.tournament ?? list.find((row) => row.id === openId);
  const bracket = detail.data?.bracket ?? [];
  const drawn = bracket.length > 0;
  const live = useMemo(() => liveMatches(bracket), [bracket]);

  // A 403 on the list would refuse the screen itself — S12 is open to every
  // role, so this is the stale-role case (§4.3), not an ordinary state.
  if (isApiError(tournaments.error) && tournaments.error.status === 403) {
    return <AccessNotice screen="Tournaments" />;
  }

  return (
    <div data-testid="tournaments-screen" className="flex min-h-0 flex-1">
      <div className="flex min-w-0 flex-1 flex-col gap-4 overflow-auto p-5">
        <SegmentedChoice<TournamentTab>
          label="Tournaments"
          options={[
            { value: 'live', label: 'Live & upcoming' },
            { value: 'history', label: 'History' },
          ]}
          value={tab}
          onChange={(next) => store().setTournamentsTab(next)}
          className="self-start"
        />

        {tournaments.isError ? (
          <p
            role="alert"
            data-testid="tournaments-error"
            className="border-2 border-accent px-3 py-2 text-body text-accent-strong"
          >
            {errorNotice(tournaments.error, 'The tournaments could not be read.')}
          </p>
        ) : null}

        {notice ? (
          <p
            role="alert"
            data-testid="winner-error"
            className="flex items-start gap-2 border-2 border-accent px-3 py-2 text-body text-accent-strong"
          >
            <AlertTriangle aria-hidden="true" className="mt-0.5 size-4 shrink-0" strokeWidth={2} />
            {notice}
          </p>
        ) : null}

        {tab === 'history' ? (
          <HistoryTable rows={history.data} loading={history.isPending} />
        ) : tournaments.isPending ? (
          <LiveSkeleton />
        ) : list.length === 0 ? (
          <EmptyState canManage={canManage} />
        ) : (
          <div className="flex items-start gap-4.5">
            <TournamentList
              rows={list}
              selectedId={openId}
              onPick={(id) => {
                setNotice(null);
                store().selectTournament(id);
              }}
            />

            <div className="flex min-w-0 flex-1 flex-col gap-4">
              {tournament ? (
                <EventHeading tournament={tournament} stationIds={detail.data?.stationIds} />
              ) : null}

              {live.length > 0 ? (
                <div className="flex gap-3">
                  {live.map((match) => (
                    <LiveMatchTile
                      key={match.id}
                      match={match}
                      receivedAt={detail.dataUpdatedAt}
                    />
                  ))}
                </div>
              ) : null}

              {tournament ? <ChampionBanner tournament={tournament} /> : null}

              {detail.isPending ? (
                <BracketSkeleton />
              ) : drawn ? (
                <>
                  <BracketView
                    matches={bracket}
                    canManage={canManage}
                    receivedAt={detail.dataUpdatedAt}
                    decidingMatchId={record.isPending ? record.variables?.matchId : null}
                    onDecide={(match, winnerEntryId) => {
                      if (typeof openId !== 'number' || typeof match.id !== 'number') return;
                      setNotice(null);
                      record.mutate(
                        { tournamentId: openId, matchId: match.id, winnerEntryId },
                        {
                          onError: (error) =>
                            setNotice(errorNotice(error, 'The winner was not recorded.')),
                        },
                      );
                    }}
                  />
                  {tournament?.status === 'LIVE' && typeof openId === 'number' ? (
                    <MatchBoard
                      tournamentId={openId}
                      matches={bracket}
                      consoles={board.data?.consoles}
                      receivedAt={board.dataUpdatedAt}
                    />
                  ) : null}
                </>
              ) : tournament ? (
                <RegisteredPlayers
                  tournament={tournament}
                  entries={detail.data?.entries}
                  canManage={canManage}
                  onNotice={setNotice}
                />
              ) : null}
            </div>
          </div>
        )}
      </div>

      {canManage ? (
        <ManagerRail
          tournament={tournament}
          stationIds={detail.data?.stationIds ?? []}
          stations={stations.data}
          finance={finance.data}
          financeLoading={finance.isPending && finance.fetchStatus !== 'idle'}
          financeError={finance.error}
        />
      ) : (
        <CashierRail />
      )}
    </div>
  );
}

/* ------------------------------------------------------------- left rail */

function TournamentList({
  rows,
  selectedId,
  onPick,
}: {
  rows: Tournament[];
  selectedId: number | null;
  onPick: (id: number) => void;
}) {
  return (
    <div data-testid="tournament-list" className="flex w-[250px] flex-none flex-col gap-2.5">
      <p className="type-label opacity-55">Tournaments</p>
      {rows.map((row) => {
        const selected = row.id === selectedId;
        return (
          <button
            key={row.id}
            type="button"
            data-testid="tournament-card"
            data-selected={selected}
            onClick={() => onPick(row.id ?? 0)}
            className={cn(
              'flex cursor-pointer flex-col gap-1 border-2 px-3.5 py-3 text-left',
              'focus-visible:outline-2 focus-visible:outline-accent focus-visible:outline-offset-2',
              selected ? 'border-accent bg-card' : 'border-divider bg-transparent',
            )}
          >
            <span className="flex items-baseline gap-2">
              <span className="flex-1 font-heading text-[14px] leading-tight font-extrabold">
                {row.name}
              </span>
              <Tag variant={tournamentStatusTag(row.status)}>{tournamentStatusLabel(row.status)}</Tag>
            </span>
            <span className="text-[11px] opacity-60">
              {tournamentMeta(row, row.scheduledAt ? formatVenueDateTime(row.scheduledAt) : 'TBD')}
            </span>
            <span className="flex justify-between text-[11px] tabular opacity-70">
              <span>{`${formatBDT(row.entryFee ?? 0)} entry`}</span>
              <span>{slotsLabel(row, row.status !== 'OPEN')}</span>
            </span>
          </button>
        );
      })}
    </div>
  );
}

/* ------------------------------------------------------------ the event */

function EventHeading({
  tournament,
  stationIds,
}: {
  tournament: Tournament;
  stationIds: number[] | undefined;
}) {
  const cadence = CADENCE_LABELS[(tournament.cadence ?? 'ONE_OFF') as TournamentCadence];
  const held = (stationIds ?? []).length;

  return (
    <div data-testid="tournament-heading">
      <div className="flex items-baseline gap-2.5">
        <h2 className="font-heading text-[26px] leading-tight font-extrabold tracking-[-0.03em]">
          {tournament.name}
        </h2>
        <Tag variant={tournamentStatusTag(tournament.status)}>
          {tournamentStatusLabel(tournament.status)}
        </Tag>
      </div>
      <p className="text-[13px] opacity-65">
        {[
          tournament.game,
          cadence,
          tournament.scheduledAt ? formatVenueDateTime(tournament.scheduledAt) : 'TBD',
          `Entry ${formatBDT(tournament.entryFee ?? 0)}`,
          `Prize ${formatBDT(tournament.prizePool ?? 0)}`,
          `${held} console${held === 1 ? '' : 's'} blocked`,
        ].join(' · ')}
      </p>
      {tournament.cancelledReason ? (
        <p className="text-[13px] text-accent-strong">{tournament.cancelledReason}</p>
      ) : null}
    </div>
  );
}

/** Before the draw: who has bought in, in sale order, with the slots note (§8). */
function RegisteredPlayers({
  tournament,
  entries,
  canManage,
  onNotice,
}: {
  tournament: Tournament;
  entries: TournamentEntry[] | undefined;
  canManage: boolean;
  onNotice: (notice: string | null) => void;
}) {
  const generate = useGenerateBracket();
  const rows = [...(entries ?? [])].sort((a, b) => (a.seed ?? 0) - (b.seed ?? 0));
  const tournamentId = tournament.id;

  return (
    <section data-testid="registered-players" className="flex max-w-[460px] flex-col gap-2.5">
      <h3 className="type-label opacity-55">Registered players</h3>

      {rows.length === 0 ? (
        <p className="border-2 border-divider p-4 text-[13px] opacity-60">
          No entries sold yet — the POS Tournament category sells them.
        </p>
      ) : (
        rows.map((entry) => (
          <div
            key={entry.id}
            data-testid="registered-player"
            className="flex justify-between border-b border-divider pb-2 text-body"
          >
            <span className="font-heading font-extrabold">{entry.playerName}</span>
            <span className="tabular opacity-50">{`#${entry.seed}`}</span>
          </div>
        ))
      )}

      <p className="text-[12px] opacity-60">{slotsNote(tournament)}</p>

      {canManage && typeof tournamentId === 'number' ? (
        <Button
          variant="primary"
          data-testid="generate-bracket"
          // Under two entries the server answers NOT_ENOUGH_PLAYERS (§3).
          disabled={rows.length < 2 || generate.isPending}
          loading={generate.isPending}
          onClick={() => {
            onNotice(null);
            generate.mutate(
              { tournamentId },
              {
                onError: (error) =>
                  onNotice(errorNotice(error, 'The bracket was not drawn.')),
              },
            );
          }}
        >
          Close registration &amp; draw the bracket
        </Button>
      ) : null}
    </section>
  );
}

/* ------------------------------------------------------------- history */

function HistoryTable({ rows, loading }: { rows: Tournament[] | undefined; loading: boolean }) {
  if (loading) return <LiveSkeleton />;

  const columns: Column<Tournament>[] = [
    {
      key: 'name',
      header: 'Tournament',
      render: (row) => <span className="font-heading font-extrabold">{row.name}</span>,
    },
    {
      key: 'date',
      header: 'Date',
      render: (row) => (
        <span className="opacity-70">
          {row.scheduledAt ? formatVenueDateTime(row.scheduledAt) : '—'}
        </span>
      ),
    },
    {
      key: 'cadence',
      header: 'Cadence',
      render: (row) => (
        <span className="opacity-70">
          {CADENCE_LABELS[(row.cadence ?? 'ONE_OFF') as TournamentCadence]}
        </span>
      ),
    },
    { key: 'entries', header: 'Entries', align: 'right', render: (row) => row.entries ?? 0 },
    {
      key: 'prize',
      header: 'Prize',
      align: 'right',
      render: (row) => formatBDT(row.prizePool ?? 0),
    },
    {
      key: 'winner',
      header: 'Winner',
      render: (row) => (
        <span className="font-heading font-extrabold text-accent-strong">
          {row.winnerName ?? (row.status === 'CANCELLED' ? 'Called off' : '—')}
        </span>
      ),
    },
  ];

  return (
    <DataTable
      columns={columns}
      rows={rows ?? []}
      rowKey={(row) => String(row.id)}
      caption="Finished and called-off tournaments"
      empty="No tournaments have finished yet."
    />
  );
}

/* -------------------------------------------------------------- states */

/** design.md §1, S12: "No tournaments scheduled". */
function EmptyState({ canManage }: { canManage: boolean }) {
  return (
    <div
      data-testid="tournaments-empty"
      className="flex max-w-md flex-col gap-2 border-2 border-divider p-6"
    >
      <p className="type-label flex items-center gap-2 text-accent-strong">
        <Trophy aria-hidden="true" className="size-4" strokeWidth={2} />
        No tournaments scheduled
      </p>
      <p className="text-body opacity-75">
        {canManage
          ? 'Arrange one in the rail — name it, pick a power-of-two cap and block the consoles it runs on.'
          : 'A manager arranges tournaments. Once one is open, entries sell from the POS Tournament category.'}
      </p>
    </div>
  );
}

/** The loading state, shaped like the two columns it becomes (design.md §1). */
function LiveSkeleton() {
  return (
    <div data-testid="tournaments-skeleton" aria-busy="true" className="flex gap-4.5">
      <div className="flex w-[250px] flex-none flex-col gap-2.5">
        {Array.from({ length: 3 }, (_, row) => (
          <div key={row} className="h-[76px] border-2 border-divider bg-surface opacity-40" />
        ))}
      </div>
      <div className="flex min-w-0 flex-1 flex-col gap-3">
        <div className="h-8 w-64 bg-surface opacity-40" />
        <BracketSkeleton />
      </div>
    </div>
  );
}

function BracketSkeleton() {
  return (
    <div aria-busy="true" className="flex gap-4">
      {Array.from({ length: 3 }, (_, column) => (
        <div key={column} className="flex flex-1 flex-col gap-3">
          {Array.from({ length: 3 - column }, (_, box) => (
            <div key={box} className="h-[70px] border-2 border-divider bg-surface opacity-40" />
          ))}
        </div>
      ))}
    </div>
  );
}

/** Re-exported for the tests that assert the N−1 claim on a rendered bracket. */
export { expectedMatchCount };
export type { TournamentMatch };
