package dev.gamersden.tournament.domain;

import dev.gamersden.common.config.VenueTime;
import dev.gamersden.common.error.ConflictException;
import dev.gamersden.common.error.ErrorCode;
import dev.gamersden.common.error.NotFoundException;
import dev.gamersden.common.error.ValidationFailedException;
import dev.gamersden.common.security.CurrentStaff;
import dev.gamersden.common.spi.MatchLookup;
import dev.gamersden.common.spi.SessionLookup;
import dev.gamersden.common.spi.StationLookup;
import dev.gamersden.tournament.repo.TournamentEntryRepository;
import dev.gamersden.tournament.repo.TournamentMatchRepository;
import dev.gamersden.tournament.repo.TournamentRepository;
import dev.gamersden.tournament.repo.TournamentStationBlockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Match execution: putting a match on a console, adding time to it, and the countdown every
 * surface renders from (docs/tournaments.md §4, invariants §5.1 and §5.6).
 *
 * <p><strong>Starting is the only place a console is claimed.</strong> An event blocks consoles
 * when a manager configures it, which reserves them against walk-ins; it does not decide which
 * match sits on which seat. That happens here, at the moment somebody presses start, against the
 * floor as it actually is — and the seat has to clear three hurdles (§4):
 *
 * <ol>
 *   <li>it is one of <em>this</em> event's allocated consoles — concurrent tournaments each draw
 *       only from their own block rows (§2);</li>
 *   <li>no undecided match is already on it — across every event, because
 *       {@code one_live_match_per_station} is a global partial unique index;</li>
 *   <li>no walk-in session is playing on it — a blocked console can still be finishing the
 *       session that was already there when it was blocked.</li>
 * </ol>
 *
 * <p>Nothing free means 409 {@code NO_FREE_CONSOLE}: the operator waits or ends a session, and the
 * job board tells them which of the two it is rather than making them guess.
 *
 * <p>The database has the final word. Two terminals starting two matches onto the last free seat
 * both pass the check and one loses at the index — that loss is caught and rendered as the same
 * 409, so the race and the ordinary refusal are indistinguishable to a client.
 *
 * <p><strong>Time is never stored, only its inputs.</strong> {@code started_at} and
 * {@code extra_min} are the whole state; {@link MatchClock} turns them into the countdown on every
 * read. Adding time therefore re-bases the board, the bracket tag, the "Now on" tile and the Floor
 * card by writing one integer (§4).
 */
@Service
public class MatchExecutionService implements MatchLookup {

    private static final Logger log = LoggerFactory.getLogger(MatchExecutionService.class);

    /** A sane ceiling on one extend — a typo, not a policy, is what this catches. */
    private static final int MAX_EXTEND_MINUTES = 240;

    private final TournamentRepository tournaments;
    private final TournamentMatchRepository matches;
    private final TournamentEntryRepository entries;
    private final TournamentStationBlockRepository blocks;
    private final StationLookup stations;
    private final SessionLookup sessions;
    private final Clock clock;

    public MatchExecutionService(TournamentRepository tournaments,
                                 TournamentMatchRepository matches,
                                 TournamentEntryRepository entries,
                                 TournamentStationBlockRepository blocks,
                                 StationLookup stations,
                                 SessionLookup sessions,
                                 Clock clock) {
        this.tournaments = tournaments;
        this.matches = matches;
        this.entries = entries;
        this.blocks = blocks;
        this.stations = stations;
        this.sessions = sessions;
        this.clock = clock;
    }

    // ---- reads --------------------------------------------------------------------------------

    /**
     * The bracket with its countdowns — what {@code GET /tournaments/{id}} carries. Every match in
     * drawing order, {@code remainingSeconds} on the ones that are on (§4).
     */
    @Transactional(readOnly = true)
    public List<LiveMatchView> bracketOf(long tournamentId, int matchDurationMin) {
        return decorate(matches.findByTournamentIdOrderByRoundAscSlotAsc(tournamentId),
                matchDurationMin, VenueTime.now(clock));
    }

    /**
     * {@code GET /tournaments/{id}/matches?pending=true} — the cashier job board (§4).
     *
     * <p>Pending is "both players known, nobody has won yet": the matches an operator could act
     * on. Ones already on a console are included — they are what the board counts down, and a
     * started match is exactly the row that says "time up — record the winner".
     */
    @Transactional(readOnly = true)
    public Board board(long tournamentId, boolean pendingOnly) {
        Tournament tournament = tournaments.findById(tournamentId)
                .orElseThrow(() -> new NotFoundException("Tournament", tournamentId));
        OffsetDateTime at = VenueTime.now(clock);
        List<TournamentMatch> found = matches.findByTournamentIdOrderByRoundAscSlotAsc(tournamentId)
                .stream()
                .filter(match -> !pendingOnly || (match.isReady() && !match.isDecided()))
                .toList();
        return new Board(decorate(found, tournament.getMatchDurationMin(), at),
                consolesOf(tournamentId));
    }

    /**
     * Every allocated console with the reason it can or cannot take a match right now — the hints
     * beside the board, in station-id order, which is also the order {@link #start} picks in.
     */
    @Transactional(readOnly = true)
    public List<ConsoleAvailability> consolesOf(long tournamentId) {
        Map<Long, TournamentMatch> occupied = occupiedStations();
        Map<Long, SessionLookup.LiveSession> live = sessions.liveSessionsByStation();
        List<ConsoleAvailability> availability = new ArrayList<>();
        for (Long stationId : allocatedStationIdsOf(tournamentId)) {
            StationLookup.StationInfo station = stations.find(stationId).orElse(null);
            if (station == null) {
                continue;
            }
            TournamentMatch hosting = occupied.get(stationId);
            ConsoleAvailability.State state;
            if (hosting != null) {
                state = ConsoleAvailability.State.MATCH_IN_PLAY;
            } else if (live.containsKey(stationId)) {
                state = ConsoleAvailability.State.WALK_IN_SESSION;
            } else if (station.underMaintenance()) {
                state = ConsoleAvailability.State.MAINTENANCE;
            } else {
                state = ConsoleAvailability.State.FREE;
            }
            availability.add(new ConsoleAvailability(stationId, station.name(), state,
                    hosting == null ? null : hosting.getId()));
        }
        return List.copyOf(availability);
    }

    /**
     * The console the next match would land on if it were started now — {@code suggestedStationId}
     * on a winner response (§4). Empty when nothing is free, which is not an error: the winner is
     * recorded either way and the operator is simply told there is no seat yet.
     */
    @Transactional(readOnly = true)
    public Optional<Long> suggestConsole(long tournamentId) {
        return consolesOf(tournamentId).stream()
                .filter(ConsoleAvailability::isFree)
                .map(ConsoleAvailability::stationId)
                .findFirst();
    }

    // ---- MatchLookup: the Floor ----------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Map<Long, MatchLookup.LiveMatch> liveMatchesByStation() {
        List<TournamentMatch> live = matches.findByStationIdNotNullAndWinnerEntryIsNull().stream()
                .filter(TournamentMatch::hasStarted)
                .toList();
        if (live.isEmpty()) {
            return Map.of();
        }
        OffsetDateTime at = VenueTime.now(clock);
        Map<Long, Tournament> byId = new HashMap<>();
        tournaments.findAllById(live.stream().map(TournamentMatch::getTournamentId).distinct()
                .toList()).forEach(tournament -> byId.put(tournament.getId(), tournament));

        Map<Long, MatchLookup.LiveMatch> byStation = new LinkedHashMap<>();
        for (TournamentMatch match : live) {
            Tournament tournament = byId.get(match.getTournamentId());
            if (tournament == null) {
                continue;
            }
            int duration = tournament.getMatchDurationMin();
            Long remaining = MatchClock.remainingSeconds(match, duration, at);
            byStation.put(match.getStationId(), new MatchLookup.LiveMatch(
                    tournament.getId(), match.getId(), tournament.getName(), match.getRound(),
                    match.getSlot(), nameOf(match.getEntryA()), nameOf(match.getEntryB()),
                    remaining == null ? 0L : remaining,
                    MatchClock.isTimeUp(match, duration, at)));
        }
        return Map.copyOf(byStation);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MatchLookup.LiveMatch> liveMatchOn(long stationId) {
        return Optional.ofNullable(liveMatchesByStation().get(stationId));
    }

    // ---- start --------------------------------------------------------------------------------

    /**
     * {@code POST /tournaments/{id}/matches/{mid}/start} — any role (§1: execution is everyone's).
     *
     * @throws ConflictException 409 {@code NO_FREE_CONSOLE} when every allocated console is taken,
     *                           409 {@code CONFLICT} when the event is not LIVE, the match is
     *                           already on, already decided, or still waiting for the round below
     */
    @Transactional
    public LiveMatchView start(long tournamentId, long matchId) {
        Tournament tournament = lock(tournamentId);
        TournamentMatch match = lockMatchOf(tournament, matchId);
        requireLive(tournament);
        requirePlayable(tournament, match);
        if (match.hasStarted()) {
            throw new ConflictException(ErrorCode.CONFLICT,
                    "Match %d is already on".formatted(matchId),
                    Map.of("matchId", matchId, "stationId", String.valueOf(match.getStationId()),
                            "startedAt", String.valueOf(match.getStartedAt())));
        }

        ConsoleAvailability seat = consolesOf(tournamentId).stream()
                .filter(ConsoleAvailability::isFree)
                .findFirst()
                .orElseThrow(() -> noFreeConsole(tournament));

        OffsetDateTime at = VenueTime.now(clock);
        match.setStationId(seat.stationId());
        match.setStartedAt(at);
        try {
            matches.saveAndFlush(match);
        } catch (DataIntegrityViolationException lostTheRace) {
            // one_live_match_per_station: somebody took the seat between the check and here.
            throw noFreeConsole(tournament);
        }
        log.info("tournament {} match {} (round {} slot {}) started by staff {} on station {} "
                        + "(\"{}\") for {} min", tournamentId, matchId, match.getRound(),
                match.getSlot(), CurrentStaff.require().id(), seat.stationId(), seat.stationName(),
                tournament.getMatchDurationMin());
        return view(match, seat.stationName(), tournament.getMatchDurationMin(), at);
    }

    // ---- extend -------------------------------------------------------------------------------

    /**
     * {@code POST /tournaments/{id}/matches/{mid}/extend} — added minutes, any role (§4).
     *
     * <p>Added minutes accumulate on {@code extra_min}, and every countdown is computed from it,
     * so one write re-bases the board, the bracket tag, the "Now on" tile and the Floor card at
     * once. Extending a match whose time is already up is the normal case, not an edge one: the
     * board row that says "time up" is exactly where +5 min gets pressed.
     *
     * @throws ConflictException 409 {@code CONFLICT} when the match has not been started or has
     *                           already been decided — there is no countdown to move
     */
    @Transactional
    public LiveMatchView extend(long tournamentId, long matchId, int minutes) {
        if (minutes < 1 || minutes > MAX_EXTEND_MINUTES) {
            throw ValidationFailedException.onField("minutes",
                    "minutes must be between 1 and " + MAX_EXTEND_MINUTES);
        }
        Tournament tournament = lock(tournamentId);
        TournamentMatch match = lockMatchOf(tournament, matchId);
        requireLive(tournament);
        if (!match.hasStarted() || match.isDecided()) {
            throw new ConflictException(ErrorCode.CONFLICT,
                    "Match %d is not being played — there is no time to add to".formatted(matchId),
                    Map.of("matchId", matchId, "started", match.hasStarted(),
                            "decided", match.isDecided()));
        }

        match.setExtraMin(match.getExtraMin() + minutes);
        OffsetDateTime at = VenueTime.now(clock);
        log.info("tournament {} match {} extended by {} min to {} min total by staff {}",
                tournamentId, matchId, minutes,
                tournament.getMatchDurationMin() + match.getExtraMin(),
                CurrentStaff.require().id());
        return view(match, stationNameOf(match.getStationId()),
                tournament.getMatchDurationMin(), at);
    }

    // ---- shapes -------------------------------------------------------------------------------

    /**
     * One match as every live surface reads it (§4).
     *
     * @param remainingSeconds {@code null} until it is started, and once it is decided
     */
    public record LiveMatchView(TournamentMatch match,
                                String stationName,
                                Long remainingSeconds,
                                boolean timeUp) {
    }

    /** The job board: the matches, and what the event's consoles are doing. */
    public record Board(List<LiveMatchView> matches, List<ConsoleAvailability> consoles) {

        public int freeConsoles() {
            return (int) consoles.stream().filter(ConsoleAvailability::isFree).count();
        }
    }

    // ---- internals ----------------------------------------------------------------------------

    private List<LiveMatchView> decorate(List<TournamentMatch> found, int matchDurationMin,
                                         OffsetDateTime at) {
        Map<Long, String> stationNames = new HashMap<>();
        return found.stream()
                .map(match -> view(match,
                        match.getStationId() == null ? null
                                : stationNames.computeIfAbsent(match.getStationId(),
                                        this::stationNameOf),
                        matchDurationMin, at))
                .toList();
    }

    private LiveMatchView view(TournamentMatch match, String stationName, int matchDurationMin,
                               OffsetDateTime at) {
        return new LiveMatchView(match, stationName,
                MatchClock.remainingSeconds(match, matchDurationMin, at),
                MatchClock.isTimeUp(match, matchDurationMin, at));
    }

    private String stationNameOf(Long stationId) {
        return stationId == null ? null
                : stations.find(stationId).map(StationLookup.StationInfo::name).orElse(null);
    }

    private String nameOf(Long entryId) {
        return entryId == null ? null
                : entries.findById(entryId).map(TournamentEntry::getPlayerName).orElse(null);
    }

    private List<Long> allocatedStationIdsOf(long tournamentId) {
        return blocks.findByIdTournamentIdOrderByIdStationIdAsc(tournamentId).stream()
                .map(TournamentStationBlock::getStationId)
                .toList();
    }

    /** Consoles held by an undecided started match, across every event (§2's global index). */
    private Map<Long, TournamentMatch> occupiedStations() {
        Map<Long, TournamentMatch> occupied = new HashMap<>();
        matches.findByStationIdNotNullAndWinnerEntryIsNull()
                .forEach(match -> occupied.put(match.getStationId(), match));
        return occupied;
    }

    private ConflictException noFreeConsole(Tournament tournament) {
        List<ConsoleAvailability> consoles = consolesOf(tournament.getId());
        return new ConflictException(ErrorCode.NO_FREE_CONSOLE,
                consoles.isEmpty()
                        ? "%s has no consoles allocated — block one before starting a match"
                                .formatted(tournament.getName())
                        : "Every console allocated to %s is busy".formatted(tournament.getName()),
                Map.of("tournamentId", tournament.getId(),
                        "consoles", consoles.stream()
                                .map(console -> Map.of("stationId", console.stationId(),
                                        "stationName", console.stationName(),
                                        "state", console.state().name(),
                                        "note", console.note()))
                                .toList()));
    }

    // ---- guards -------------------------------------------------------------------------------

    private Tournament lock(long tournamentId) {
        return tournaments.findByIdForUpdate(tournamentId)
                .orElseThrow(() -> new NotFoundException("Tournament", tournamentId));
    }

    private TournamentMatch lockMatchOf(Tournament tournament, long matchId) {
        TournamentMatch match = matches.findByIdForUpdate(matchId)
                .orElseThrow(() -> new NotFoundException("Match", matchId));
        if (!match.getTournamentId().equals(tournament.getId())) {
            throw new NotFoundException("Match", matchId);
        }
        return match;
    }

    private static void requireLive(Tournament tournament) {
        if (tournament.getStatus() != TournamentStatus.LIVE) {
            throw new ConflictException(ErrorCode.CONFLICT,
                    "%s is %s — matches are only played while it is LIVE"
                            .formatted(tournament.getName(), tournament.getStatus()),
                    Map.of("tournamentId", tournament.getId(),
                            "status", tournament.getStatus().name()));
        }
    }

    private static void requirePlayable(Tournament tournament, TournamentMatch match) {
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
}
