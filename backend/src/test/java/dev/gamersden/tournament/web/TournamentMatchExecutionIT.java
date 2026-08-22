package dev.gamersden.tournament.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamersden.support.AbstractApiIntegrationTest;
import dev.gamersden.support.FloorFixtures;
import dev.gamersden.support.MutableClock;
import dev.gamersden.support.MutableClockConfig;
import dev.gamersden.support.TournamentFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Match execution end to end (docs/tournaments.md §4, invariants §5.1 and §5.6).
 *
 * <p>Four things are being held down here.
 *
 * <ol>
 *   <li><strong>A blocked console is not the same as a free one.</strong> Start takes the first
 *       allocated console that is neither hosting an unfinished match nor busy with a walk-in
 *       session, and refuses with 409 {@code NO_FREE_CONSOLE} rather than double-booking a
 *       seat.</li>
 *   <li><strong>The clock is the server's.</strong> Every countdown is recomputed from
 *       {@code started_at}, {@code extra_min} and the venue clock, so adding time re-bases the
 *       bracket, the board and the Floor card from one write.</li>
 *   <li><strong>Starting is execution.</strong> Any role may put a match on a console and add time
 *       to it — a cashier running the counter is who actually does it.</li>
 *   <li><strong>A decided match hands its console back.</strong> The partial unique index is
 *       keyed on undecided matches, so recording a winner frees the seat for the next one — and
 *       the response says which seat that is.</li>
 * </ol>
 */
@Import(MutableClockConfig.class)
class TournamentMatchExecutionIT extends AbstractApiIntegrationTest {

    private static final int FEE = 200;
    private static final int MATCH_MINUTES = 20;

    @Autowired
    private MutableClock clock;

    private FloorFixtures floor;
    private TournamentFixtures fixtures;
    private HttpHeaders manager;
    private HttpHeaders cashier;
    private Long stationA;
    private Long stationB;
    private Long shiftId;
    private Long cashierId;

    @BeforeEach
    void seed() {
        clock.resetToNow();
        floor = new FloorFixtures(jdbc);
        fixtures = new TournamentFixtures(jdbc);
        manager = adminBearer();
        stationA = createStation("PS5-01", "PS5");
        stationB = createStation("PS5-02", "PS5");
        shiftId = floor.openShift(adminId, TERMINAL);
        cashierId = post("/api/v1/staff",
                Map.of("name", "Rafi", "role", "CASHIER", "pin", "4321"), manager)
                .getBody().get("id").asLong();
        cashier = bearerFor(cashierId, "4321");
    }

    /**
     * Pushing the venue clock forward pushes the access tokens with it — everything server-side
     * reads the same {@link java.time.Clock}, so a 15-minute token really has expired by the time
     * a 20-minute match is over. Signing back in is what an operator would be doing anyway.
     */
    private void jumpForward(Duration amount) {
        clock.advance(amount);
        manager = adminBearer();
        cashier = bearerFor(cashierId, "4321");
    }

    // ---- console assignment -----------------------------------------------------------------

    @Test
    @DisplayName("start takes the first allocated console and stamps the match on")
    void startsOnTheFirstFreeConsole() {
        Long id = liveTournament(stationA, stationB);
        List<Long> semis = semiFinalIds(id);

        JsonNode started = start(id, semis.get(0), cashier);

        assertThat(started.get("stationId").asLong()).isEqualTo(stationA);
        assertThat(started.get("stationName").asText()).isEqualTo("PS5-01");
        assertThat(started.get("remainingSeconds").asLong()).isEqualTo(MATCH_MINUTES * 60);
        assertThat(started.get("timeUp").asBoolean()).isFalse();
        assertThat(started.hasNonNull("startedAt")).isTrue();

        Map<String, Object> row = matchById(id, semis.get(0));
        assertThat(row.get("station_id")).isEqualTo(stationA);
        assertThat(row.get("started_at")).as("started_at is stamped server-side").isNotNull();
        assertThat(row.get("winner_entry")).isNull();
    }

    @Test
    @DisplayName("a console already hosting an unfinished match is skipped")
    void skipsAConsoleWithAMatchOnIt() {
        Long id = liveTournament(stationA, stationB);
        List<Long> semis = semiFinalIds(id);

        start(id, semis.get(0), manager);
        JsonNode second = start(id, semis.get(1), manager);

        assertThat(second.get("stationId").asLong())
                .as("one live match per console — the second match takes the next seat")
                .isEqualTo(stationB);
        assertThat(matchById(id, semis.get(0)).get("station_id")).isEqualTo(stationA);
    }

    @Test
    @DisplayName("a console busy with a walk-in session is skipped, session untouched")
    void skipsAConsoleWithAWalkInSession() {
        Long id = liveTournament(stationA, stationB);
        // A session that was already running when the manager blocked the console: the block
        // stops new walk-ins, it does not evict the one that is already playing (§4).
        Long sessionId = floor.runningSessionOn(stationA, shiftId, 2, 80, 0);
        List<Long> semis = semiFinalIds(id);

        JsonNode started = start(id, semis.get(0), manager);

        assertThat(started.get("stationId").asLong()).isEqualTo(stationB);
        assertThat(floor.stateOf(sessionId)).as("the walk-in keeps its seat").isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("every allocated console busy is 409 NO_FREE_CONSOLE, and says what each is doing")
    void noFreeConsole() {
        Long id = liveTournament(stationA, stationB);
        floor.runningSessionOn(stationA, shiftId, 2, 80, 0);
        List<Long> semis = semiFinalIds(id);
        start(id, semis.get(0), manager);   // takes PS5-02, the only other seat

        ResponseEntity<JsonNode> refused = startRaw(id, semis.get(1), manager);

        assertErrorEnvelope(refused, 409, "NO_FREE_CONSOLE");
        JsonNode consoles = refused.getBody().get("error").get("details").get("consoles");
        assertThat(consoles).hasSize(2);
        assertThat(consoles.get(0).get("state").asText()).isEqualTo("WALK_IN_SESSION");
        assertThat(consoles.get(0).get("note").asText())
                .isEqualTo("Allocated console busy with a walk-in session");
        assertThat(consoles.get(1).get("state").asText()).isEqualTo("MATCH_IN_PLAY");
        assertThat(matchById(id, semis.get(1)).get("station_id"))
                .as("a refused start leaves the match where it was").isNull();
        assertThat(matchById(id, semis.get(1)).get("started_at")).isNull();
    }

    @Test
    @DisplayName("an event holding no consoles cannot start anything")
    void noAllocationAtAll() {
        Long id = liveTournament();

        assertErrorEnvelope(startRaw(id, semiFinalIds(id).get(0), manager), 409, "NO_FREE_CONSOLE");
    }

    @Test
    @DisplayName("a console under maintenance hosts nothing")
    void skipsMaintenance() {
        Long id = liveTournament(stationA, stationB);
        patch("/api/v1/stations/" + stationA, Map.of("status", "MAINTENANCE"), manager);

        assertThat(start(id, semiFinalIds(id).get(0), manager).get("stationId").asLong())
                .isEqualTo(stationB);
    }

    @Test
    @DisplayName("concurrent events draw only from their own blocks")
    void eachEventDrawsFromItsOwnBlocks() {
        Long first = liveTournament(stationA);
        Long second = liveTournament("Sunday Tekken", stationB);
        start(first, semiFinalIds(first).get(0), manager);

        assertThat(start(second, semiFinalIds(second).get(0), manager).get("stationId").asLong())
                .isEqualTo(stationB);
        // The first event's remaining semi has nowhere to go — PS5-02 is not one of its consoles.
        assertErrorEnvelope(startRaw(first, semiFinalIds(first).get(1), manager),
                409, "NO_FREE_CONSOLE");
    }

    @Test
    @DisplayName("a match is started once, and only while it is playable")
    void startGuards() {
        Long id = liveTournament(stationA, stationB);
        List<Long> semis = semiFinalIds(id);
        Long finalId = finalId(id);

        // The final has nobody in it yet.
        assertErrorEnvelope(startRaw(id, finalId, manager), 409, "CONFLICT");

        start(id, semis.get(0), manager);
        assertErrorEnvelope(startRaw(id, semis.get(0), manager), 409, "CONFLICT");

        decide(id, semis.get(0), (Long) matchById(id, semis.get(0)).get("entry_a"), manager);
        assertErrorEnvelope(startRaw(id, semis.get(0), manager), 409, "CONFLICT");

        // Another event's match is not this event's to start.
        Long other = liveTournament("Sunday Tekken", stationB);
        assertErrorEnvelope(startRaw(id, semiFinalIds(other).get(0), manager), 404, "NOT_FOUND");
    }

    @Test
    @DisplayName("a match that has not been drawn or whose event is not live cannot start")
    void onlyLiveEventsPlay() {
        Long id = fixtures.openTournament("Friday FIFA", FEE, 8, adminId);
        fixtures.block(id, stationA);
        sell(id, "Rifat", "Nafis", "Tanvir");
        post("/api/v1/tournaments/" + id + "/bracket", null, manager);
        Long playable = playableFirstRoundId(id);
        fixtures.setStatus(id, "DONE");

        assertErrorEnvelope(startRaw(id, playable, manager), 409, "CONFLICT");
    }

    // ---- the countdown ------------------------------------------------------------------------

    @Test
    @DisplayName("adding time re-bases the countdown without re-stamping the start")
    void extendRebasesRemainingSeconds() {
        Long id = liveTournament(stationA, stationB);
        Long semi = semiFinalIds(id).get(0);
        JsonNode started = start(id, semi, cashier);
        String startedAt = started.get("startedAt").asText();

        jumpForward(Duration.ofMinutes(18));
        assertThat(remainingOf(id, semi)).isEqualTo(2 * 60);

        JsonNode extended = extend(id, semi, 5, cashier);

        assertThat(extended.get("extraMinutes").asInt()).isEqualTo(5);
        assertThat(extended.get("remainingSeconds").asLong()).isEqualTo(7 * 60);
        assertThat(OffsetDateTime.parse(extended.get("startedAt").asText()).toInstant())
                .as("the clock is re-based by extra_min alone — nothing is re-stamped")
                .isEqualTo(OffsetDateTime.parse(startedAt).toInstant());
        assertThat(remainingOf(id, semi)).isEqualTo(7 * 60);

        // Past zero the countdown floors and the row reads "time up — record the winner".
        jumpForward(Duration.ofMinutes(8));
        JsonNode timedOut = matchViewOf(id, semi);
        assertThat(timedOut.get("remainingSeconds").asLong()).isZero();
        assertThat(timedOut.get("timeUp").asBoolean()).isTrue();

        // +10 on an expired match brings it back, and extensions accumulate.
        JsonNode revived = extend(id, semi, 10, cashier);
        assertThat(revived.get("extraMinutes").asInt()).isEqualTo(15);
        assertThat(revived.get("remainingSeconds").asLong()).isEqualTo(9 * 60);
        assertThat(revived.get("timeUp").asBoolean()).isFalse();
        assertThat(matchById(id, semi).get("extra_min")).isEqualTo(15);
    }

    @Test
    @DisplayName("only a match in play has time to add to")
    void extendGuards() {
        Long id = liveTournament(stationA, stationB);
        Long semi = semiFinalIds(id).get(0);

        assertErrorEnvelope(extendRaw(id, semi, 5, manager), 409, "CONFLICT");

        start(id, semi, manager);
        assertErrorEnvelope(extendRaw(id, semi, 0, manager), 400, "VALIDATION_FAILED");
        assertThat(matchById(id, semi).get("extra_min")).isEqualTo(0);

        decide(id, semi, (Long) matchById(id, semi).get("entry_a"), manager);
        assertErrorEnvelope(extendRaw(id, semi, 5, manager), 409, "CONFLICT");
    }

    @Test
    @DisplayName("an un-started match carries no countdown at all")
    void unstartedMatchesHaveNoClock() {
        Long id = liveTournament(stationA, stationB);
        JsonNode match = matchViewOf(id, semiFinalIds(id).get(0));

        assertThat(match.hasNonNull("remainingSeconds")).isFalse();
        assertThat(match.get("timeUp").asBoolean()).isFalse();
        assertThat(match.hasNonNull("stationId")).isFalse();
    }

    // ---- the job board ------------------------------------------------------------------------

    @Test
    @DisplayName("pending=true is the ready matches plus what each console is doing")
    void jobBoard() {
        Long id = liveTournament(stationA, stationB);
        floor.runningSessionOn(stationA, shiftId, 2, 80, 0);
        List<Long> semis = semiFinalIds(id);

        JsonNode board = get("/api/v1/tournaments/" + id + "/matches?pending=true", cashier)
                .getBody();

        assertThat(board.get("matches")).as("the final is still waiting for the round below")
                .hasSize(2);
        assertThat(board.get("freeConsoles").asInt()).isEqualTo(1);
        assertThat(board.get("consoles")).hasSize(2);
        assertThat(board.get("consoles").get(0).get("stationName").asText()).isEqualTo("PS5-01");
        assertThat(board.get("consoles").get(0).get("available").asBoolean()).isFalse();
        assertThat(board.get("consoles").get(0).get("note").asText())
                .isEqualTo("Allocated console busy with a walk-in session");
        assertThat(board.get("consoles").get(1).get("available").asBoolean()).isTrue();

        start(id, semis.get(0), cashier);
        JsonNode after = get("/api/v1/tournaments/" + id + "/matches?pending=true", cashier)
                .getBody();

        assertThat(after.get("freeConsoles").asInt()).isZero();
        assertThat(after.get("consoles").get(1).get("state").asText()).isEqualTo("MATCH_IN_PLAY");
        assertThat(after.get("consoles").get(1).get("matchId").asLong()).isEqualTo(semis.get(0));
        assertThat(after.get("matches")).as("a started match stays on the board until it is decided")
                .hasSize(2);

        decide(id, semis.get(0), (Long) matchById(id, semis.get(0)).get("entry_a"), cashier);
        JsonNode decided = get("/api/v1/tournaments/" + id + "/matches?pending=true", cashier)
                .getBody();
        assertThat(decided.get("matches")).hasSize(1);
        assertThat(decided.get("freeConsoles")).as("a decided match hands its console back")
                .isNotNull();
        assertThat(decided.get("freeConsoles").asInt()).isEqualTo(1);
        assertThat(decided.get("consoles").get(1).get("available").asBoolean()).isTrue();

        // Without the flag the whole bracket comes back.
        assertThat(get("/api/v1/tournaments/" + id + "/matches", cashier).getBody().get("matches"))
                .hasSize(3);
    }

    // ---- winners ------------------------------------------------------------------------------

    @Test
    @DisplayName("a result says which console the advanced player's next match would take")
    void winnerSuggestsTheNextConsole() {
        Long id = liveTournament(stationA, stationB);
        List<Long> semis = semiFinalIds(id);
        start(id, semis.get(0), cashier);
        start(id, semis.get(1), cashier);

        JsonNode first = decide(id, semis.get(0), (Long) matchById(id, semis.get(0)).get("entry_a"),
                cashier);

        assertThat(first.get("nextMatchId").asLong()).isEqualTo(finalId(id));
        assertThat(first.get("suggestedStationId").asLong())
                .as("the seat that match just freed is the seat the final would take")
                .isEqualTo(stationA);
        assertThat(first.get("champion").asBoolean()).isFalse();

        decide(id, semis.get(1), (Long) matchById(id, semis.get(1)).get("entry_b"), cashier);

        // Both semis are decided, so both seats are back — the final takes the first of them.
        assertThat(start(id, finalId(id), cashier).get("stationId").asLong()).isEqualTo(stationA);
        JsonNode crowned = decide(id, finalId(id),
                (Long) matchById(id, finalId(id)).get("entry_a"), cashier);

        assertThat(crowned.get("champion").asBoolean()).isTrue();
        assertThat(crowned.hasNonNull("nextMatchId")).isFalse();
        assertThat(crowned.hasNonNull("suggestedStationId"))
                .as("there is no next match to seat").isFalse();
        assertThat(crowned.get("tournament").get("status").asText()).isEqualTo("DONE");
        assertThat(crowned.get("tournament").get("winnerName").asText()).isNotBlank();
    }

    @Test
    @DisplayName("nothing free means no suggestion, which is information rather than an error")
    void winnerWithNoFreeConsole() {
        Long id = liveTournament(stationA);
        List<Long> semis = semiFinalIds(id);
        start(id, semis.get(0), manager);
        floor.runningSessionOn(stationA, shiftId, 2, 80, 0);

        JsonNode decided = decide(id, semis.get(0),
                (Long) matchById(id, semis.get(0)).get("entry_a"), manager);

        assertThat(decided.get("nextMatchId").asLong()).isEqualTo(finalId(id));
        assertThat(decided.hasNonNull("suggestedStationId")).isFalse();
    }

    // ---- the floor ------------------------------------------------------------------------------

    @Test
    @DisplayName("a reserved console counts its match down on the Floor like a session")
    void theFloorShowsTheMatch() {
        Long id = liveTournament(stationA, stationB);
        Long semi = semiFinalIds(id).get(0);

        JsonNode beforeStart = get("/api/v1/stations/" + stationA, cashier).getBody();
        assertThat(beforeStart.get("floorState").asText()).isEqualTo("RESERVED");
        assertThat(beforeStart.has("match")).as("reserved, but nothing on it yet").isFalse();

        start(id, semi, cashier);
        jumpForward(Duration.ofMinutes(5));

        JsonNode card = get("/api/v1/stations/" + stationA, cashier).getBody();
        assertThat(card.get("floorState").asText()).isEqualTo("RESERVED");
        JsonNode match = card.get("match");
        assertThat(match.get("matchId").asLong()).isEqualTo(semi);
        assertThat(match.get("tournamentName").asText()).isEqualTo("Friday FIFA");
        assertThat(match.get("remainingSeconds").asLong()).isEqualTo(15 * 60);
        assertThat(match.get("timeUp").asBoolean()).isFalse();
        assertThat(match.get("playerA").asText()).isNotBlank();
        assertThat(match.get("playerB").asText()).isNotBlank();

        jumpForward(Duration.ofMinutes(20));
        assertThat(get("/api/v1/stations/" + stationA, cashier).getBody()
                .get("match").get("timeUp").asBoolean()).isTrue();

        // The grid tells the same story, and hands the seat back once the match is decided.
        JsonNode grid = get("/api/v1/stations", cashier).getBody();
        assertThat(grid.get(0).get("match").get("matchId").asLong()).isEqualTo(semi);
        decide(id, semi, (Long) matchById(id, semi).get("entry_a"), cashier);
        assertThat(get("/api/v1/stations/" + stationA, cashier).getBody().has("match")).isFalse();
    }

    // ---- helpers -----------------------------------------------------------------------------------

    /** A drawn, LIVE 4-player event holding the given consoles. */
    private Long liveTournament(Long... stationIds) {
        return liveTournament("Friday FIFA", stationIds);
    }

    private Long liveTournament(String name, Long... stationIds) {
        Long id = fixtures.openTournament(name, FEE, 4, adminId);
        for (Long stationId : stationIds) {
            fixtures.block(id, stationId);
        }
        sell(id, "Rifat", "Nafis", "Tanvir", "Shuvo");
        assertThat(fixtures.statusOf(id)).isEqualTo("LIVE");
        return id;
    }

    private List<Long> semiFinalIds(Long tournamentId) {
        return fixtures.matchesOf(tournamentId).stream()
                .filter(match -> (int) match.get("round") == 1)
                .map(match -> (Long) match.get("id"))
                .toList();
    }

    private Long finalId(Long tournamentId) {
        return fixtures.matchesOf(tournamentId).stream()
                .filter(match -> match.get("next_match_id") == null)
                .map(match -> (Long) match.get("id"))
                .findFirst().orElseThrow();
    }

    /** The one first-round match of an undersubscribed bracket that is actually played. */
    private Long playableFirstRoundId(Long tournamentId) {
        return fixtures.matchesOf(tournamentId).stream()
                .filter(match -> match.get("entry_a") != null && match.get("entry_b") != null
                        && match.get("winner_entry") == null)
                .map(match -> (Long) match.get("id"))
                .findFirst().orElseThrow();
    }

    private ResponseEntity<JsonNode> startRaw(Long tournamentId, Long matchId, HttpHeaders who) {
        return post("/api/v1/tournaments/" + tournamentId + "/matches/" + matchId + "/start", null,
                who);
    }

    private JsonNode start(Long tournamentId, Long matchId, HttpHeaders who) {
        ResponseEntity<JsonNode> started = startRaw(tournamentId, matchId, who);
        assertThat(started.getStatusCode()).as("start refused: %s", started.getBody())
                .isEqualTo(HttpStatus.OK);
        return started.getBody();
    }

    private ResponseEntity<JsonNode> extendRaw(Long tournamentId, Long matchId, int minutes,
                                               HttpHeaders who) {
        return post("/api/v1/tournaments/" + tournamentId + "/matches/" + matchId + "/extend",
                Map.of("minutes", minutes), who);
    }

    private JsonNode extend(Long tournamentId, Long matchId, int minutes, HttpHeaders who) {
        ResponseEntity<JsonNode> extended = extendRaw(tournamentId, matchId, minutes, who);
        assertThat(extended.getStatusCode()).as("extend refused: %s", extended.getBody())
                .isEqualTo(HttpStatus.OK);
        return extended.getBody();
    }

    private JsonNode decide(Long tournamentId, Long matchId, Long entryId, HttpHeaders who) {
        ResponseEntity<JsonNode> recorded = post(
                "/api/v1/tournaments/" + tournamentId + "/matches/" + matchId + "/winner",
                Map.of("winnerEntryId", entryId), who);
        assertThat(recorded.getStatusCode()).as("winner refused: %s", recorded.getBody())
                .isEqualTo(HttpStatus.OK);
        return recorded.getBody();
    }

    private JsonNode matchViewOf(Long tournamentId, Long matchId) {
        JsonNode bracket = get("/api/v1/tournaments/" + tournamentId, manager).getBody()
                .get("bracket");
        for (JsonNode match : bracket) {
            if (match.get("id").asLong() == matchId) {
                return match;
            }
        }
        throw new AssertionError("match " + matchId + " not in the bracket");
    }

    private long remainingOf(Long tournamentId, Long matchId) {
        return matchViewOf(tournamentId, matchId).get("remainingSeconds").asLong();
    }

    private Map<String, Object> matchById(Long tournamentId, Long matchId) {
        return fixtures.matchesOf(tournamentId).stream()
                .filter(match -> matchId.equals(match.get("id")))
                .findFirst().orElseThrow();
    }

    private void sell(Long tournamentId, String... players) {
        List<Map<String, Object>> lines = new ArrayList<>(players.length);
        for (String player : players) {
            lines.add(Map.of("tournamentId", tournamentId, "playerName", player));
        }
        ResponseEntity<JsonNode> settled = post("/api/v1/payments", Map.of(
                "target", Map.of(),
                "tournamentEntries", lines,
                "splits", List.of(Map.of("method", "CASH", "amount", players.length * FEE))),
                withKey(manager));
        assertThat(settled.getStatusCode()).as("settle failed: %s", settled.getBody())
                .isEqualTo(HttpStatus.CREATED);
    }

    private HttpHeaders withKey(HttpHeaders who) {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(who);
        headers.add("Idempotency-Key", UUID.randomUUID().toString());
        return headers;
    }

    private Long createStation(String name, String consoleType) {
        return post("/api/v1/stations", Map.of("name", name, "consoleType", consoleType), manager)
                .getBody().get("id").asLong();
    }
}
