package dev.gamersden.tournament.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamersden.support.AbstractApiIntegrationTest;
import dev.gamersden.support.FloorFixtures;
import dev.gamersden.support.TournamentFixtures;
import dev.gamersden.tournament.domain.BracketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.IllegalTransactionStateException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The bracket engine end to end (docs/tournaments.md §3, invariant §5.6).
 *
 * <p>Four things are being held down here.
 *
 * <ol>
 *   <li><strong>The draw belongs to the sale.</strong> The ticket that fills the last slot and the
 *       bracket it completes are written by one commit — and a settle that is refused leaves
 *       neither behind.</li>
 *   <li><strong>A cap is a perfect bracket.</strong> 4, 8, 16 and 32 all produce exactly N−1
 *       matches with nobody walking through.</li>
 *   <li><strong>An undersubscribed event still plays.</strong> Manual generate draws the next size
 *       up and walks the byes off the board before anybody looks at it.</li>
 *   <li><strong>Results move up the tree and let go at the top.</strong> Winning the final makes a
 *       champion, turns the event DONE and hands back every console it was holding — with the
 *       role split between execution and ruling enforced on the way (§1, §4).</li>
 * </ol>
 */
class TournamentBracketIT extends AbstractApiIntegrationTest {

    private static final int FEE = 200;

    @Autowired
    private BracketService brackets;

    private FloorFixtures floor;
    private TournamentFixtures fixtures;
    private HttpHeaders manager;
    private HttpHeaders cashier;
    private Long stationId;
    private Long shiftId;

    @BeforeEach
    void seed() {
        floor = new FloorFixtures(jdbc);
        fixtures = new TournamentFixtures(jdbc);
        manager = adminBearer();
        stationId = createStation("PS5-01", "PS5");
        shiftId = floor.openShift(adminId, TERMINAL);
        Long cashierId = post("/api/v1/staff",
                Map.of("name", "Rafi", "role", "CASHIER", "pin", "4321"), manager)
                .getBody().get("id").asLong();
        cashier = bearerFor(cashierId, "4321");
    }

    // ---- auto-generate, inside the sale ---------------------------------------------------------

    @Test
    @DisplayName("the sale that fills the last slot draws the bracket and flips the event LIVE")
    void capFillingSaleDrawsTheBracket() {
        Long id = fixtures.openTournament("Friday FIFA", FEE, 4, adminId);
        fixtures.block(id, stationId);

        // Three sold: still selling, still no bracket.
        sell(id, "Rifat", "Nafis", "Tanvir");
        assertThat(fixtures.statusOf(id)).isEqualTo("OPEN");
        assertThat(fixtures.matchesOf(id)).isEmpty();

        long lastSale = sell(id, "Shuvo");

        assertThat(fixtures.statusOf(id)).isEqualTo("LIVE");
        List<Map<String, Object>> bracket = fixtures.matchesOf(id);
        assertThat(bracket).hasSize(3);
        assertThat(bracket).allSatisfy(match -> assertThat(match.get("winner_entry"))
                .as("a full event has no byes — nothing is decided at the draw").isNull());

        // The bracket the last sale drew is made of the entries the sales wrote, seeds and all.
        List<Long> entryIds = entryIdsOf(id);
        assertThat(bracket.stream().filter(match -> (int) match.get("round") == 1)
                .flatMap(match -> java.util.stream.Stream.of(match.get("entry_a"), match.get("entry_b")))
                .toList())
                .containsExactlyInAnyOrderElementsOf(entryIds);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tournament_entries WHERE tx_id = ?",
                Integer.class, lastSale)).isEqualTo(1);

        // And the door is shut behind it: no fifth ticket, no second draw.
        assertErrorEnvelope(sellExpectingRefusal(id, "Late"), 409, "TOURNAMENT_NOT_OPEN");
    }

    @ParameterizedTest(name = "cap {0}")
    @ValueSource(ints = {4, 8, 16, 32})
    @DisplayName("every cap draws exactly N-1 matches, a single final, and no byes")
    void everyCapIsAPerfectBracket(int cap) {
        Long id = fixtures.openTournament("Cap " + cap, FEE, cap, adminId);
        sell(id, IntStream.rangeClosed(1, cap).mapToObj(seed -> "P" + seed).toArray(String[]::new));

        assertThat(fixtures.statusOf(id)).isEqualTo("LIVE");
        List<Map<String, Object>> bracket = fixtures.matchesOf(id);
        assertThat(bracket).hasSize(cap - 1);

        int rounds = Integer.numberOfTrailingZeros(cap);
        for (int round = 1; round <= rounds; round++) {
            int expected = cap >> round;
            int actual = round;
            assertThat(bracket.stream().filter(match -> (int) match.get("round") == actual).count())
                    .as("round %d of the %d bracket", round, cap).isEqualTo(expected);
        }
        // Exactly one match ends the tree, and everything else points somewhere.
        assertThat(bracket.stream().filter(match -> match.get("next_match_id") == null).toList())
                .hasSize(1);
        assertThat(bracket).allSatisfy(match -> {
            boolean firstRound = (int) match.get("round") == 1;
            assertThat(match.get("entry_a") != null && match.get("entry_b") != null)
                    .as("only the first round is seated at the draw").isEqualTo(firstRound);
            assertThat(match.get("winner_entry")).isNull();
        });
        assertThat(bracket.stream().map(match -> match.get("next_match_id")).filter(java.util.Objects::nonNull)
                .distinct().count())
                .as("two feeders per slot above").isEqualTo((cap - 1) - (cap / 2));
    }

    @Test
    @DisplayName("a refused settle leaves no entry, no money and no bracket")
    void theDrawRollsBackWithItsSale() {
        Long id = fixtures.openTournament("Friday FIFA", FEE, 4, adminId);
        sell(id, "Rifat", "Nafis", "Tanvir");
        int transactionsBefore = countOf("transactions");

        // The last slot, tendered short: the settle is refused before anything is committed.
        ResponseEntity<JsonNode> refused = post("/api/v1/payments", Map.of(
                "target", Map.of(),
                "tournamentEntries", List.of(Map.of("tournamentId", id, "playerName", "Shuvo")),
                "splits", List.of(Map.of("method", "CASH", "amount", FEE - 50))),
                withKey(manager));

        assertErrorEnvelope(refused, 409, "SPLIT_MISMATCH");
        assertThat(fixtures.entriesOf(id)).hasSize(3);
        assertThat(fixtures.matchesOf(id)).isEmpty();
        assertThat(fixtures.statusOf(id)).isEqualTo("OPEN");
        assertThat(countOf("transactions")).isEqualTo(transactionsBefore);
    }

    @Test
    @DisplayName("the cap-fill draw cannot be reached outside a money transaction")
    void autoGenerateIsMandatoryInsideATransaction() {
        Long id = fixtures.openTournament("Friday FIFA", FEE, 4, adminId);

        assertThatThrownBy(() -> brackets.generateIfFull(id))
                .as("Propagation.MANDATORY is what makes \"in the same transaction\" structural")
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    // ---- manual generate ------------------------------------------------------------------------

    @Test
    @DisplayName("an undersubscribed event plays the next size up, byes already advanced")
    void manualGenerateWalksTheByesOff() {
        Long id = fixtures.openTournament("Friday FIFA", FEE, 8, adminId);
        sell(id, "Rifat", "Nafis", "Tanvir", "Shuvo", "Arif");
        List<Long> entries = entryIdsOf(id);

        ResponseEntity<JsonNode> drawn = post("/api/v1/tournaments/" + id + "/bracket", null, manager);

        assertThat(drawn.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(drawn.getBody().get("tournament").get("status").asText()).isEqualTo("LIVE");
        assertThat(drawn.getBody().get("bracket")).hasSize(7);

        List<Map<String, Object>> bracket = fixtures.matchesOf(id);
        List<Map<String, Object>> firstRound = bracket.stream()
                .filter(match -> (int) match.get("round") == 1).toList();
        assertThat(firstRound).hasSize(4);

        // Standard placement: seeds 1, 2 and 3 walk through, 4 plays 5.
        List<Map<String, Object>> byes = firstRound.stream()
                .filter(match -> match.get("entry_a") == null || match.get("entry_b") == null)
                .toList();
        assertThat(byes).hasSize(3);
        assertThat(byes).extracting(match -> match.get("winner_entry"))
                .containsExactlyInAnyOrder(entries.get(0), entries.get(1), entries.get(2));
        assertThat(byes).allSatisfy(bye -> {
            assertThat(bye.get("decided_by")).as("the draw decided it, and a manager drew it")
                    .isEqualTo(adminId);
            assertThat(bye.get("decided_at")).isNotNull();
            assertThat(bye.get("station_id")).as("a bye is never played on a console").isNull();
            assertThat(bye.get("started_at")).isNull();
        });

        // The one real first-round match is 4 v 5, and it is the only one still open.
        Map<String, Object> played = firstRound.stream()
                .filter(match -> match.get("entry_a") != null && match.get("entry_b") != null)
                .findFirst().orElseThrow();
        assertThat(List.of(played.get("entry_a"), played.get("entry_b")))
                .containsExactlyInAnyOrder(entries.get(3), entries.get(4));
        assertThat(played.get("winner_entry")).isNull();

        // Round two already has three of its four places filled, and no bye reached it.
        List<Map<String, Object>> secondRound = bracket.stream()
                .filter(match -> (int) match.get("round") == 2).toList();
        assertThat(secondRound).hasSize(2);
        assertThat(secondRound).allSatisfy(match ->
                assertThat(match.get("winner_entry")).as("byes never cascade").isNull());
        assertThat(secondRound.stream()
                .flatMap(match -> java.util.stream.Stream.of(match.get("entry_a"), match.get("entry_b")))
                .filter(java.util.Objects::nonNull).toList())
                .containsExactlyInAnyOrder(entries.get(0), entries.get(1), entries.get(2));
    }

    @Test
    @DisplayName("one player is not a tournament — 409 NOT_ENOUGH_PLAYERS")
    void oneIsNotABracket() {
        Long id = fixtures.openTournament("Friday FIFA", FEE, 8, adminId);
        sell(id, "Rifat");

        assertErrorEnvelope(post("/api/v1/tournaments/" + id + "/bracket", null, manager),
                409, "NOT_ENOUGH_PLAYERS");
        assertThat(fixtures.matchesOf(id)).isEmpty();
        assertThat(fixtures.statusOf(id)).isEqualTo("OPEN");

        // Nobody at all is the same answer.
        Long empty = fixtures.openTournament("Empty Cup", FEE, 8, adminId);
        assertErrorEnvelope(post("/api/v1/tournaments/" + empty + "/bracket", null, manager),
                409, "NOT_ENOUGH_PLAYERS");
    }

    @Test
    @DisplayName("drawing a bracket is configuration — a cashier gets the 403 envelope")
    void cashierCannotDrawTheBracket() {
        Long id = fixtures.openTournament("Friday FIFA", FEE, 8, adminId);
        sell(id, "Rifat", "Nafis");

        assertErrorEnvelope(post("/api/v1/tournaments/" + id + "/bracket", null, cashier),
                403, "FORBIDDEN");
        assertThat(fixtures.matchesOf(id)).isEmpty();
    }

    @Test
    @DisplayName("a bracket is drawn once — a second generate is refused, never a redraw")
    void noRedraw() {
        Long id = fixtures.openTournament("Friday FIFA", FEE, 8, adminId);
        sell(id, "Rifat", "Nafis", "Tanvir");
        post("/api/v1/tournaments/" + id + "/bracket", null, manager);
        List<Object> drawn = fixtures.matchesOf(id).stream().map(match -> match.get("id")).toList();

        assertErrorEnvelope(post("/api/v1/tournaments/" + id + "/bracket", null, manager),
                409, "TOURNAMENT_NOT_OPEN");
        assertThat(fixtures.matchesOf(id)).extracting(match -> match.get("id"))
                .containsExactlyElementsOf(drawn);
    }

    // ---- winners ---------------------------------------------------------------------------------

    @Test
    @DisplayName("a winner walks up the tree; the final crowns, closes and releases the consoles")
    void propagationToTheFinal() {
        Long id = fixtures.openTournament("Friday FIFA", FEE, 4, adminId);
        fixtures.block(id, stationId);
        sell(id, "Rifat", "Nafis", "Tanvir", "Shuvo");

        // The console is held while the event runs.
        assertErrorEnvelope(post("/api/v1/sessions", Map.of("stationId", stationId), manager),
                409, "STATION_RESERVED");

        List<Map<String, Object>> bracket = fixtures.matchesOf(id);
        Map<String, Object> semiOne = bracket.get(0);
        Map<String, Object> semiTwo = bracket.get(1);
        Long finalId = (Long) bracket.get(2).get("id");
        assertThat(semiOne.get("next_match_id")).isEqualTo(finalId);
        assertThat(semiTwo.get("next_match_id")).isEqualTo(finalId);

        Long winnerOne = (Long) semiOne.get("entry_a");
        Long winnerTwo = (Long) semiTwo.get("entry_b");
        decide(id, (Long) semiOne.get("id"), winnerOne, manager);
        decide(id, (Long) semiTwo.get("id"), winnerTwo, manager);

        // Slot 1 feeds the top half of the final, slot 2 the bottom.
        Map<String, Object> theFinal = matchById(id, finalId);
        assertThat(theFinal.get("entry_a")).isEqualTo(winnerOne);
        assertThat(theFinal.get("entry_b")).isEqualTo(winnerTwo);
        assertThat(fixtures.statusOf(id)).isEqualTo("LIVE");

        JsonNode crowned = decide(id, finalId, winnerTwo, manager);

        assertThat(crowned.get("tournament").get("status").asText()).isEqualTo("DONE");
        assertThat(crowned.get("tournament").get("winnerEntryId").asLong()).isEqualTo(winnerTwo);
        assertThat(fixtures.winnerEntryOf(id)).isEqualTo(winnerTwo);
        assertThat(matchById(id, finalId).get("decided_by")).isEqualTo(adminId);
        assertThat(matchById(id, finalId).get("decided_at")).isNotNull();

        // DONE releases every console the event held, with no second write (§2).
        assertThat(post("/api/v1/sessions", Map.of("stationId", stationId), manager).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(countOf("tournament_station_blocks")).isEqualTo(1);
    }

    @Test
    @DisplayName("a cashier may record a started match but not decide one nobody started")
    void theRoleSplitFollowsTheMatch() {
        Long id = fixtures.openTournament("Friday FIFA", FEE, 4, adminId);
        sell(id, "Rifat", "Nafis", "Tanvir", "Shuvo");
        List<Map<String, Object>> bracket = fixtures.matchesOf(id);
        Long semiOne = (Long) bracket.get(0).get("id");
        Long semiTwo = (Long) bracket.get(1).get("id");

        // Un-started: a ruling, and rulings are Manager+.
        assertErrorEnvelope(winner(id, semiOne, (Long) bracket.get(0).get("entry_a"), cashier),
                403, "FORBIDDEN");
        assertThat(matchById(id, semiOne).get("winner_entry")).isNull();

        // Started: execution, and execution is everyone's.
        fixtures.startMatch(semiOne);
        Long playing = (Long) bracket.get(0).get("entry_b");
        assertThat(winner(id, semiOne, playing, cashier).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(matchById(id, semiOne).get("winner_entry")).isEqualTo(playing);
        assertThat(matchById(id, semiOne).get("decided_by"))
                .as("every result records who entered it (§1)")
                .isEqualTo(jdbc.queryForObject("SELECT id FROM staff WHERE name = 'Rafi'",
                        Long.class));

        // The same manager who was refused the ruling can make it.
        assertThat(winner(id, semiTwo, (Long) bracket.get(1).get("entry_a"), manager)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a result has to name somebody actually playing that match")
    void winnerMustBeAParticipant() {
        Long id = fixtures.openTournament("Friday FIFA", FEE, 4, adminId);
        sell(id, "Rifat", "Nafis", "Tanvir", "Shuvo");
        List<Map<String, Object>> bracket = fixtures.matchesOf(id);
        Long semiOne = (Long) bracket.get(0).get("id");

        assertErrorEnvelope(winner(id, semiOne, (Long) bracket.get(1).get("entry_a"), manager),
                409, "CONFLICT");
        assertThat(matchById(id, semiOne).get("winner_entry")).isNull();
    }

    @Test
    @DisplayName("a match still waiting for the round below, or already decided, is refused")
    void theTreeIsPlayedInOrderAndOnlyOnce() {
        Long id = fixtures.openTournament("Friday FIFA", FEE, 4, adminId);
        sell(id, "Rifat", "Nafis", "Tanvir", "Shuvo");
        List<Map<String, Object>> bracket = fixtures.matchesOf(id);
        Long semiOne = (Long) bracket.get(0).get("id");
        Long finalId = (Long) bracket.get(2).get("id");
        Long entry = (Long) bracket.get(0).get("entry_a");

        assertErrorEnvelope(winner(id, finalId, entry, manager), 409, "CONFLICT");

        decide(id, semiOne, entry, manager);
        assertErrorEnvelope(winner(id, semiOne, entry, manager), 409, "CONFLICT");
        assertThat(matchById(id, semiOne).get("winner_entry")).isEqualTo(entry);
    }

    @Test
    @DisplayName("results are only recorded while the event is live, and only for its own matches")
    void resultsBelongToALiveEvent() {
        Long id = fixtures.openTournament("Friday FIFA", FEE, 8, adminId);
        Long other = fixtures.openTournament("Sunday Tekken", FEE, 4, adminId);
        sell(id, "Rifat", "Nafis");
        sell(other, "A", "B", "C", "D");
        Long foreign = (Long) fixtures.matchesOf(other).get(0).get("id");

        // Not drawn yet: nothing to record against.
        assertErrorEnvelope(winner(id, foreign, 1L, manager), 404, "NOT_FOUND");

        post("/api/v1/tournaments/" + id + "/bracket", null, manager);
        assertErrorEnvelope(winner(id, foreign, 1L, manager), 404, "NOT_FOUND");
        assertThat(fixtures.matchesOf(other).get(0).get("winner_entry")).isNull();
    }

    // ---- the read ---------------------------------------------------------------------------------

    @Test
    @DisplayName("GET /tournaments/{id} carries the bracket with the players' names on it")
    void theBracketRidesAlongWithTheDetail() {
        Long id = fixtures.openTournament("Friday FIFA", FEE, 4, adminId);
        JsonNode beforeDraw = get("/api/v1/tournaments/" + id, cashier).getBody();
        assertThat(beforeDraw.get("bracket")).isEmpty();

        sell(id, "Rifat", "Nafis", "Tanvir", "Shuvo");
        JsonNode detail = get("/api/v1/tournaments/" + id, cashier).getBody();

        assertThat(detail.get("bracket")).hasSize(3);
        JsonNode first = detail.get("bracket").get(0);
        assertThat(first.get("round").asInt()).isEqualTo(1);
        assertThat(first.get("slot").asInt()).isEqualTo(1);
        assertThat(first.get("playerA").asText()).isEqualTo("Rifat");
        assertThat(first.get("playerB").asText()).as("standard seeding puts 1 against the last")
                .isEqualTo("Shuvo");
        assertThat(first.get("bye").asBoolean()).isFalse();
        assertThat(first.hasNonNull("winnerEntryId")).isFalse();
        assertThat(first.get("extraMinutes").asInt()).isZero();

        JsonNode last = detail.get("bracket").get(2);
        assertThat(last.get("round").asInt()).isEqualTo(2);
        assertThat(last.hasNonNull("nextMatchId")).as("the final ends the tree").isFalse();
        assertThat(last.hasNonNull("playerA")).as("nobody has reached it yet").isFalse();
    }

    // ---- helpers -----------------------------------------------------------------------------------

    /** One counter sale covering every named player, and the transaction it wrote. */
    private long sell(Long tournamentId, String... players) {
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
        return settled.getBody().get("transactionId").asLong();
    }

    private ResponseEntity<JsonNode> sellExpectingRefusal(Long tournamentId, String player) {
        return post("/api/v1/payments", Map.of(
                "target", Map.of(),
                "tournamentEntries", List.of(Map.of("tournamentId", tournamentId,
                        "playerName", player)),
                "splits", List.of(Map.of("method", "CASH", "amount", FEE))),
                withKey(manager));
    }

    private ResponseEntity<JsonNode> winner(Long tournamentId, Long matchId, Long entryId,
                                            HttpHeaders who) {
        return post("/api/v1/tournaments/" + tournamentId + "/matches/" + matchId + "/winner",
                Map.of("winnerEntryId", entryId), who);
    }

    private JsonNode decide(Long tournamentId, Long matchId, Long entryId, HttpHeaders who) {
        ResponseEntity<JsonNode> recorded = winner(tournamentId, matchId, entryId, who);
        assertThat(recorded.getStatusCode()).as("winner refused: %s", recorded.getBody())
                .isEqualTo(HttpStatus.OK);
        return recorded.getBody();
    }

    private Map<String, Object> matchById(Long tournamentId, Long matchId) {
        return fixtures.matchesOf(tournamentId).stream()
                .filter(match -> matchId.equals(match.get("id")))
                .findFirst().orElseThrow();
    }

    private List<Long> entryIdsOf(Long tournamentId) {
        return fixtures.entriesOf(tournamentId).stream()
                .map(entry -> (Long) entry.get("id")).toList();
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

    private int countOf(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
