package dev.gamersden.tournament.domain;

import dev.gamersden.auth.domain.StaffRole;
import dev.gamersden.common.config.VenueTime;
import dev.gamersden.common.error.ConflictException;
import dev.gamersden.common.error.ErrorCode;
import dev.gamersden.common.error.ForbiddenException;
import dev.gamersden.common.error.NotFoundException;
import dev.gamersden.common.events.LiveEvents;
import dev.gamersden.common.security.CurrentStaff;
import dev.gamersden.common.security.StaffPrincipal;
import dev.gamersden.common.spi.SyncOutboxWriter;
import dev.gamersden.tournament.repo.TournamentEntryRepository;
import dev.gamersden.tournament.repo.TournamentMatchRepository;
import dev.gamersden.tournament.repo.TournamentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The bracket: drawing it, and moving winners up it (docs/tournaments.md §3, invariant §5.6).
 *
 * <p>There are two ways a bracket comes into being and they are deliberately different.
 *
 * <p><strong>Auto-generate</strong> happens inside the sale that fills the last slot —
 * {@link Propagation#MANDATORY}, called from {@link TournamentEntryService#register} while the
 * tournament row is still locked by the quote that priced the ticket. The player who bought the
 * final entry and the bracket they completed are written by the same commit: there is no window in
 * which an event is full but undrawn, and a settle that rolls back takes the bracket with it. A
 * full event is a perfect bracket — cap 4/8/16/32, exactly N−1 matches, no byes.
 *
 * <p><strong>Manual generate</strong> is the Manager+ escape hatch for an event that never filled
 * (§3). Fewer players means empty positions, and an empty position is a bye: the match is written
 * decided, its one player already advanced, and nobody ever sits down to play it. Below two
 * players there is no bracket to draw at all — 409 {@code NOT_ENOUGH_PLAYERS}.
 *
 * <p><strong>Propagation</strong> is the same operation in both cases. A winner is written onto
 * their match and copied into the slot above along {@code next_match_id}; when the match that was
 * won is the final, the tournament takes its champion, turns DONE and — by that status alone,
 * since a block only holds a console while its event is OPEN or LIVE (§2) — hands every reserved
 * console back to the floor.
 */
@Service
public class BracketService {

    private static final Logger log = LoggerFactory.getLogger(BracketService.class);

    private static final Comparator<TournamentMatch> DRAWING_ORDER =
            Comparator.comparingInt(TournamentMatch::getRound)
                    .thenComparingInt(TournamentMatch::getSlot);

    private final TournamentRepository tournaments;
    private final TournamentEntryRepository entries;
    private final TournamentMatchRepository matches;
    private final LiveEvents live;
    private final SyncOutboxWriter outbox;
    private final Clock clock;

    public BracketService(TournamentRepository tournaments, TournamentEntryRepository entries,
                          TournamentMatchRepository matches, LiveEvents live,
                          SyncOutboxWriter outbox, Clock clock) {
        this.tournaments = tournaments;
        this.entries = entries;
        this.matches = matches;
        this.live = live;
        this.outbox = outbox;
        this.clock = clock;
    }

    // ---- reads --------------------------------------------------------------------------------

    /** The bracket in drawing order; empty until it has been generated. */
    @Transactional(readOnly = true)
    public List<TournamentMatch> of(long tournamentId) {
        return matches.findByTournamentIdOrderByRoundAscSlotAsc(tournamentId);
    }

    // ---- generation ---------------------------------------------------------------------------

    /**
     * {@code POST /tournaments/{id}/bracket} — draw it now (Manager+, guarded on the controller).
     *
     * @throws ConflictException 409 {@code NOT_ENOUGH_PLAYERS} under two players, 409
     *                           {@code TOURNAMENT_NOT_OPEN} once the event is no longer selling,
     *                           409 {@code CONFLICT} if a bracket already exists
     */
    @Transactional
    public List<TournamentMatch> generate(long tournamentId) {
        Tournament tournament = lock(tournamentId);
        if (tournament.getStatus() != TournamentStatus.OPEN) {
            throw new ConflictException(ErrorCode.TOURNAMENT_NOT_OPEN,
                    "%s is %s — a bracket is only drawn while it is OPEN"
                            .formatted(tournament.getName(), tournament.getStatus()),
                    Map.of("tournamentId", tournamentId, "status", tournament.getStatus().name()));
        }
        List<TournamentEntry> players = playersOf(tournamentId);
        if (players.size() < BracketPlan.MIN_PLAYERS) {
            throw new ConflictException(ErrorCode.NOT_ENOUGH_PLAYERS,
                    "%s has %d player(s) — a bracket needs at least %d"
                            .formatted(tournament.getName(), players.size(),
                                    BracketPlan.MIN_PLAYERS),
                    Map.of("tournamentId", tournamentId, "entries", players.size()));
        }
        return draw(tournament, players);
    }

    /**
     * The cap-fill hook, called from inside the settle that sold the last entry.
     *
     * <p>Does nothing at all unless this sale actually completed the field, so the ordinary sale
     * path pays for one count and nothing else. {@link Propagation#MANDATORY} is the structural
     * half of "in the same transaction" (§3): there is no caller that could reach this outside a
     * money transaction, so a bracket can never appear without the ticket that filled it.
     *
     * @return the bracket if this sale drew it, otherwise empty
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<TournamentMatch> generateIfFull(long tournamentId) {
        Tournament tournament = lock(tournamentId);
        if (tournament.getStatus() != TournamentStatus.OPEN
                || entries.countByTournamentId(tournamentId) < tournament.getMaxPlayers()) {
            return List.of();
        }
        List<TournamentEntry> players = playersOf(tournamentId);
        if (players.size() < BracketPlan.MIN_PLAYERS) {
            return List.of();
        }
        log.info("tournament {} (\"{}\") filled its {} slots — drawing the bracket in the sale's "
                + "transaction", tournamentId, tournament.getName(), tournament.getMaxPlayers());
        return draw(tournament, players);
    }

    /**
     * Writes the plan out, final first.
     *
     * <p>The order matters: {@code next_match_id} is a real foreign key, so a match can only be
     * inserted once the match it feeds already has an id. Working down from the final means every
     * link is known at insert time and no row is ever written twice.
     */
    private List<TournamentMatch> draw(Tournament tournament, List<TournamentEntry> players) {
        long tournamentId = tournament.getId();
        if (matches.existsByTournamentId(tournamentId)) {
            throw new ConflictException(ErrorCode.CONFLICT,
                    "%s already has a bracket".formatted(tournament.getName()),
                    Map.of("tournamentId", tournamentId));
        }
        BracketPlan plan =
                BracketPlan.forSeeds(players.stream().map(TournamentEntry::getId).toList());

        Map<Integer, TournamentMatch> written = new HashMap<>();
        for (int round = plan.rounds(); round >= 1; round--) {
            for (BracketPlan.PlannedMatch planned : plan.roundOf(round)) {
                TournamentMatch next = written.get(key(round + 1, upperSlot(planned.slot())));
                written.put(key(round, planned.slot()),
                        matches.save(new TournamentMatch(tournamentId, round, planned.slot(),
                                planned.entryA(), planned.entryB(),
                                next == null ? null : next.getId())));
            }
        }

        tournament.setStatus(TournamentStatus.LIVE);
        List<TournamentMatch> bracket = new ArrayList<>(written.values());
        bracket.sort(DRAWING_ORDER);
        advanceByes(bracket);

        log.info("tournament {} (\"{}\") bracket drawn by staff {}: {} players in a {}-slot "
                        + "bracket — {} matches, {} bye(s), status LIVE", tournamentId,
                tournament.getName(), CurrentStaff.require().id(), players.size(), plan.size(),
                bracket.size(), plan.byes());
        outbox.record(SyncOutboxWriter.TOURNAMENTS, SyncOutboxWriter.BRACKET_DRAWN, tournamentId,
                SyncOutboxWriter.data("players", players.size(),
                        "slots", plan.size(),
                        "matches", bracket.size(),
                        "byes", plan.byes(),
                        "matchIds", bracket.stream().map(TournamentMatch::getId).toList()));
        live.tournamentChanged(tournamentId);
        return List.copyOf(bracket);
    }

    /**
     * Walks the empty positions off the board before anybody looks at it.
     *
     * <p>Only ever the first round: {@link BracketPlan} places the empties so that no bye can meet
     * another, so nothing here cascades (see that class for why). Their winners carry the
     * generating operator in {@code decided_by} — the draw is what decided them, and the draw was
     * that operator's doing.
     */
    private void advanceByes(List<TournamentMatch> bracket) {
        StaffPrincipal staff = CurrentStaff.require();
        OffsetDateTime at = VenueTime.now(clock);
        Map<Long, TournamentMatch> byId = new HashMap<>();
        bracket.forEach(match -> byId.put(match.getId(), match));

        bracket.stream().filter(TournamentMatch::isBye).forEach(bye -> {
            Long advancing = bye.getEntryA() != null ? bye.getEntryA() : bye.getEntryB();
            decide(bye, advancing, staff.id(), at);
            feed(byId.get(bye.getNextMatchId()), bye, advancing);
            log.info("tournament {} match {} (round {} slot {}) is a bye — entry {} advances",
                    bye.getTournamentId(), bye.getId(), bye.getRound(), bye.getSlot(), advancing);
        });
    }

    // ---- winners ------------------------------------------------------------------------------

    /**
     * {@code POST /tournaments/{id}/matches/{mid}/winner} — the result, and everything it moves.
     *
     * <p>Who may record it is decided by the match, not by the route (§1, §4). A match that has
     * been started is execution: the operator who sat the two players down in front of a console
     * is the operator who saw who won, so any role may enter it. A match nobody started is a
     * ruling — a walkover, a disqualification, a result taken on somebody's word — and that is
     * configuration: Manager+, 403 otherwise.
     *
     * @throws ConflictException 409 {@code CONFLICT} when the event is not LIVE, when the match is
     *                           already decided, when it does not yet have both players, or when
     *                           the named winner is not one of them
     */
    @Transactional
    public Decision recordWinner(long tournamentId, long matchId, long winnerEntryId) {
        Tournament tournament = lock(tournamentId);
        TournamentMatch match = matches.findByIdForUpdate(matchId)
                .orElseThrow(() -> new NotFoundException("Match", matchId));
        if (!match.getTournamentId().equals(tournamentId)) {
            throw new NotFoundException("Match", matchId);
        }
        requireLive(tournament);
        requireDecidable(tournament, match);
        requireAuthority(match);
        if (!match.has(winnerEntryId)) {
            throw new ConflictException(ErrorCode.CONFLICT,
                    "Entry %d is not playing match %d".formatted(winnerEntryId, matchId),
                    Map.of("matchId", matchId, "entryA", String.valueOf(match.getEntryA()),
                            "entryB", String.valueOf(match.getEntryB())));
        }

        StaffPrincipal staff = CurrentStaff.require();
        decide(match, winnerEntryId, staff.id(), VenueTime.now(clock));

        TournamentMatch next = null;
        boolean champion = match.isFinal();
        if (champion) {
            tournament.setWinnerEntryId(winnerEntryId);
            tournament.setStatus(TournamentStatus.DONE);
        } else {
            next = matches.findByIdForUpdate(match.getNextMatchId())
                    .orElseThrow(() -> new NotFoundException("Match", match.getNextMatchId()));
            feed(next, match, winnerEntryId);
        }
        log.info("tournament {} match {} (round {} slot {}) won by entry {}, recorded by staff {}"
                        + "{}", tournamentId, matchId, match.getRound(), match.getSlot(),
                winnerEntryId, staff.id(),
                champion ? " — the final: tournament DONE, consoles released"
                        : " — advances to match " + next.getId());
        outbox.record(SyncOutboxWriter.TOURNAMENT_MATCHES, SyncOutboxWriter.WON, matchId,
                SyncOutboxWriter.data("tournamentId", tournamentId,
                        "round", match.getRound(),
                        "slot", match.getSlot(),
                        "winnerEntryId", winnerEntryId,
                        "decidedBy", staff.id(),
                        "champion", champion,
                        "nextMatchId", next == null ? null : next.getId()));
        live.tournamentChanged(tournamentId);
        if (match.getStationId() != null) {
            // The console the match was on is free again — and on the final, so is every other one
            // the event was holding, which the DONE status releases (docs/tournaments.md §2).
            live.stationChanged(match.getStationId());
        }
        return new Decision(tournament, match, next, champion);
    }

    /**
     * What one result did.
     *
     * @param next     the match the winner advanced into, or {@code null} when this was the final
     * @param champion true when the tournament now has its winner and has released its consoles
     */
    public record Decision(Tournament tournament,
                           TournamentMatch match,
                           TournamentMatch next,
                           boolean champion) {
    }

    // ---- shared writes ------------------------------------------------------------------------

    private static void decide(TournamentMatch match, Long winnerEntryId, Long staffId,
                               OffsetDateTime at) {
        match.setWinnerEntry(winnerEntryId);
        match.setDecidedBy(staffId);
        match.setDecidedAt(at);
    }

    /** Drops a winner into their side of the match above — top half for odd slots, bottom for even. */
    private static void feed(TournamentMatch next, TournamentMatch from, Long entryId) {
        if (next == null) {
            return;
        }
        if (from.feedsSideA()) {
            next.setEntryA(entryId);
        } else {
            next.setEntryB(entryId);
        }
    }

    // ---- guards -------------------------------------------------------------------------------

    private Tournament lock(long tournamentId) {
        return tournaments.findByIdForUpdate(tournamentId)
                .orElseThrow(() -> new NotFoundException("Tournament", tournamentId));
    }

    /** Everyone who is still in: a refunded ticket is a player who went home. */
    private List<TournamentEntry> playersOf(long tournamentId) {
        return entries.findByTournamentIdOrderBySeedAsc(tournamentId).stream()
                .filter(entry -> !entry.isRefunded())
                .toList();
    }

    private static void requireLive(Tournament tournament) {
        if (tournament.getStatus() != TournamentStatus.LIVE) {
            throw new ConflictException(ErrorCode.CONFLICT,
                    "%s is %s — results are only recorded while it is LIVE"
                            .formatted(tournament.getName(), tournament.getStatus()),
                    Map.of("tournamentId", tournament.getId(),
                            "status", tournament.getStatus().name()));
        }
    }

    private static void requireDecidable(Tournament tournament, TournamentMatch match) {
        if (match.isDecided()) {
            throw new ConflictException(ErrorCode.CONFLICT,
                    "Match %d in %s already has a winner"
                            .formatted(match.getId(), tournament.getName()),
                    Map.of("matchId", match.getId(), "winnerEntryId", match.getWinnerEntry()));
        }
        if (!match.isReady()) {
            throw new ConflictException(ErrorCode.CONFLICT,
                    "Match %d is still waiting for the round below".formatted(match.getId()),
                    Map.of("matchId", match.getId(), "round", match.getRound(),
                            "slot", match.getSlot()));
        }
    }

    /**
     * Started matches are execution and belong to whoever is running the floor; un-started ones
     * are a ruling and belong to a manager (§1, §4).
     */
    private static void requireAuthority(TournamentMatch match) {
        if (!match.hasStarted() && !CurrentStaff.require().isAtLeast(StaffRole.MANAGER)) {
            throw new ForbiddenException(
                    "Match %d has not been started — deciding it is a Manager call"
                            .formatted(match.getId()));
        }
    }

    /** Slots {@code 2k-1} and {@code 2k} both feed slot {@code k} of the round above. */
    private static int upperSlot(int slot) {
        return (slot + 1) / 2;
    }

    private static int key(int round, int slot) {
        return round * 100 + slot;
    }
}
