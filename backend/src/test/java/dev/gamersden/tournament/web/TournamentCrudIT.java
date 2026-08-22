package dev.gamersden.tournament.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamersden.support.AbstractApiIntegrationTest;
import dev.gamersden.support.TournamentFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tournament configuration end to end (docs/tournaments.md §1–§2): who may write it, what the
 * bracket rules refuse, and what stops being editable once money has changed hands.
 */
class TournamentCrudIT extends AbstractApiIntegrationTest {

    private static final String CASHIER_PIN = "4321";

    private TournamentFixtures fixtures;
    private HttpHeaders manager;

    @BeforeEach
    void seed() {
        fixtures = new TournamentFixtures(jdbc);
        manager = adminBearer();
    }

    // ---- RBAC — the authoritative layer (docs/tournaments.md §1) --------------------------------

    @Test
    @DisplayName("a cashier may read the board and sell, but creating an event is 403")
    void cashierCannotConfigure() {
        HttpHeaders cashier = cashierBearer();
        Long existing = fixtures.openTournament("Friday FIFA", 200, 8, adminId);

        assertErrorEnvelope(post("/api/v1/tournaments", validBody("Saturday Tekken"), cashier),
                403, "FORBIDDEN");
        assertErrorEnvelope(patch("/api/v1/tournaments/" + existing, Map.of("prizePool", 9000),
                cashier), 403, "FORBIDDEN");
        assertErrorEnvelope(put("/api/v1/tournaments/" + existing + "/blocks",
                Map.of("stationIds", List.of()), cashier), 403, "FORBIDDEN");
        assertErrorEnvelope(post("/api/v1/tournaments/" + existing + "/cancel",
                Map.of("reason", "nope"), cashier), 403, "FORBIDDEN");

        // Reading is theirs, and nothing above was written.
        assertThat(get("/api/v1/tournaments", cashier).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(countOf("tournaments")).isEqualTo(1);
        assertThat(fixtures.statusOf(existing)).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("an anonymous caller sees nothing")
    void anonymousIsRejected() {
        assertThat(get("/api/v1/tournaments", null).getStatusCode().value()).isEqualTo(401);
    }

    // ---- create ---------------------------------------------------------------------------------

    @Test
    @DisplayName("a manager creates an event and it comes back with its slot count")
    void createReturnsTheCard() {
        ResponseEntity<JsonNode> created =
                post("/api/v1/tournaments", validBody("Friday FIFA"), manager);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode card = created.getBody().get("tournament");
        assertThat(card.get("name").asText()).isEqualTo("Friday FIFA");
        assertThat(card.get("status").asText()).isEqualTo("OPEN");
        assertThat(card.get("maxPlayers").asInt()).isEqualTo(8);
        assertThat(card.get("entries").asInt()).isZero();
        assertThat(card.get("slotsLeft").asInt()).isEqualTo(8);
        assertThat(created.getBody().get("entries")).isEmpty();
        assertThat(created.getBody().get("stationIds")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT created_by FROM tournaments WHERE id = ?",
                Long.class, card.get("id").asLong())).isEqualTo(adminId);
    }

    @Test
    @DisplayName("only a power-of-two cap builds a perfect bracket")
    void capMustBeAPowerOfTwo() {
        assertErrorEnvelope(post("/api/v1/tournaments", body("Six Player", 6), manager),
                400, "VALIDATION_FAILED");
        assertErrorEnvelope(post("/api/v1/tournaments", body("Sixty Four", 64), manager),
                400, "VALIDATION_FAILED");
        assertThat(countOf("tournaments")).isZero();

        for (int cap : new int[] {4, 8, 16, 32}) {
            assertThat(post("/api/v1/tournaments", body("Cap " + cap, cap), manager)
                    .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
        assertThat(countOf("tournaments")).isEqualTo(4);
    }

    @Test
    @DisplayName("two events cannot share a name")
    void duplicateName() {
        post("/api/v1/tournaments", validBody("Friday FIFA"), manager);

        assertErrorEnvelope(post("/api/v1/tournaments", validBody("Friday FIFA"), manager),
                409, "DUPLICATE_NAME");
        assertThat(countOf("tournaments")).isEqualTo(1);
    }

    // ---- edit -----------------------------------------------------------------------------------

    @Test
    @DisplayName("an OPEN event is editable field by field")
    void patchEditsWhatItIsGiven() {
        Long id = fixtures.openTournament("Friday FIFA", 200, 8, adminId);

        JsonNode card = patch("/api/v1/tournaments/" + id,
                Map.of("name", "Friday FIFA Cup", "prizePool", 9000, "maxPlayers", 16), manager)
                .getBody().get("tournament");

        assertThat(card.get("name").asText()).isEqualTo("Friday FIFA Cup");
        assertThat(card.get("prizePool").asInt()).isEqualTo(9000);
        assertThat(card.get("maxPlayers").asInt()).isEqualTo(16);
        // Untouched fields stay put.
        assertThat(card.get("entryFee").asInt()).isEqualTo(200);
        assertThat(card.get("game").asText()).isEqualTo("FIFA 25");
    }

    @Test
    @DisplayName("a LIVE event is no longer configuration")
    void patchNeedsAnOpenEvent() {
        Long id = fixtures.openTournament("Friday FIFA", 200, 8, adminId);
        fixtures.setStatus(id, "LIVE");

        assertErrorEnvelope(patch("/api/v1/tournaments/" + id, Map.of("prizePool", 9000), manager),
                409, "TOURNAMENT_NOT_OPEN");
    }

    @Test
    @DisplayName("once a ticket is sold the fee is frozen and the cap cannot drop below it")
    void soldEntriesFreezeTheMoneyFields() {
        openShift();
        Long id = fixtures.openTournament("Friday FIFA", 200, 8, adminId);
        sellEntry(id, "Rifat");
        sellEntry(id, "Nafis");

        // The fee is what a cancel has to hand back, so it cannot move under the entries.
        assertErrorEnvelope(patch("/api/v1/tournaments/" + id, Map.of("entryFee", 300), manager),
                409, "CONFLICT");
        // A cap of 2 is not a bracket at all; 4 still holds the two who bought in.
        assertErrorEnvelope(patch("/api/v1/tournaments/" + id, Map.of("maxPlayers", 2), manager),
                400, "VALIDATION_FAILED");
        JsonNode narrowed = patch("/api/v1/tournaments/" + id, Map.of("maxPlayers", 4), manager)
                .getBody().get("tournament");
        assertThat(narrowed.get("maxPlayers").asInt()).isEqualTo(4);
        assertThat(narrowed.get("slotsLeft").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT entry_fee FROM tournaments WHERE id = ?",
                Integer.class, id)).isEqualTo(200);
    }

    @Test
    @DisplayName("the cap cannot drop below the entries already sold")
    void capCannotStrandSoldEntries() {
        openShift();
        Long id = fixtures.openTournament("Friday FIFA", 200, 16, adminId);
        for (String player : new String[] {"Rifat", "Nafis", "Tanvir", "Sabbir", "Imran"}) {
            sellEntry(id, player);
        }

        assertErrorEnvelope(patch("/api/v1/tournaments/" + id, Map.of("maxPlayers", 4), manager),
                409, "CONFLICT");
        assertThat(jdbc.queryForObject("SELECT max_players FROM tournaments WHERE id = ?",
                Integer.class, id)).isEqualTo(16);
        // Eight still fits all five.
        assertThat(patch("/api/v1/tournaments/" + id, Map.of("maxPlayers", 8), manager)
                .getBody().get("tournament").get("slotsLeft").asInt()).isEqualTo(3);
    }

    // ---- station blocks --------------------------------------------------------------------------

    @Test
    @DisplayName("blocks replace the whole allocation and an empty list releases it")
    void blocksAreReplacedWholesale() {
        Long ps5 = createStation("PS5-01", "PS5");
        Long ps4 = createStation("PS4-01", "PS4");
        Long id = fixtures.openTournament("Friday FIFA", 200, 8, adminId);

        JsonNode both = put("/api/v1/tournaments/" + id + "/blocks",
                Map.of("stationIds", List.of(ps5, ps4)), manager).getBody();
        assertThat(both.get("stationIds")).hasSize(2);

        JsonNode one = put("/api/v1/tournaments/" + id + "/blocks",
                Map.of("stationIds", List.of(ps4)), manager).getBody();
        assertThat(one.get("stationIds")).hasSize(1);
        assertThat(one.get("stationIds").get(0).asLong()).isEqualTo(ps4);

        put("/api/v1/tournaments/" + id + "/blocks", Map.of("stationIds", List.of()), manager);
        assertThat(countOf("tournament_station_blocks")).isZero();
    }

    @Test
    @DisplayName("blocking a station that does not exist is 404, and writes nothing")
    void blocksMustNameRealStations() {
        Long ps5 = createStation("PS5-01", "PS5");
        Long id = fixtures.openTournament("Friday FIFA", 200, 8, adminId);
        put("/api/v1/tournaments/" + id + "/blocks", Map.of("stationIds", List.of(ps5)), manager);

        assertErrorEnvelope(put("/api/v1/tournaments/" + id + "/blocks",
                Map.of("stationIds", List.of(ps5, 9_999L)), manager), 404, "NOT_FOUND");
        assertThat(countOf("tournament_station_blocks")).isEqualTo(1);
    }

    // ---- reads -------------------------------------------------------------------------------------

    @Test
    @DisplayName("the list is what is still selling; history is what is over")
    void listAndHistorySplitOnStatus() {
        Long open = fixtures.openTournament("Friday FIFA", 200, 8, adminId);
        Long live = fixtures.openTournament("Tekken Night", 200, 8, adminId);
        fixtures.setStatus(live, "LIVE");
        Long done = fixtures.openTournament("Last Month", 200, 8, adminId);
        fixtures.setStatus(done, "DONE");
        Long cancelled = fixtures.openTournament("Rained Off", 200, 8, adminId);
        fixtures.setStatus(cancelled, "CANCELLED");

        assertThat(idsOf(get("/api/v1/tournaments", manager))).containsExactlyInAnyOrder(open, live);
        assertThat(idsOf(get("/api/v1/tournaments/history", manager)))
                .containsExactlyInAnyOrder(done, cancelled);
    }

    @Test
    @DisplayName("an unknown event is 404")
    void unknownIsNotFound() {
        assertErrorEnvelope(get("/api/v1/tournaments/9999", manager), 404, "NOT_FOUND");
    }

    // ---- helpers -------------------------------------------------------------------------------------

    private static Map<String, Object> validBody(String name) {
        return body(name, 8);
    }

    private static Map<String, Object> body(String name, int maxPlayers) {
        return Map.of("name", name, "game", "FIFA 25", "cadence", "WEEKLY",
                "scheduledAt", "2026-09-04T19:00:00+06:00", "entryFee", 200, "prizePool", 5000,
                "maxPlayers", maxPlayers, "matchDurationMin", 20);
    }

    private List<Long> idsOf(ResponseEntity<JsonNode> response) {
        return java.util.stream.StreamSupport
                .stream(response.getBody().spliterator(), false)
                .map(card -> card.get("id").asLong())
                .toList();
    }

    private void sellEntry(Long tournamentId, String playerName) {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(manager);
        headers.add("Idempotency-Key", java.util.UUID.randomUUID().toString());
        ResponseEntity<JsonNode> sold = post("/api/v1/tournaments/" + tournamentId + "/entries",
                Map.of("playerName", playerName,
                        "splits", List.of(Map.of("method", "CASH", "amount", 200))), headers);
        assertThat(sold.getStatusCode()).as("entry sale failed: %s", sold.getBody())
                .isEqualTo(HttpStatus.CREATED);
    }

    private HttpHeaders cashierBearer() {
        Long id = post("/api/v1/staff",
                Map.of("name", "Rafi", "role", "CASHIER", "pin", CASHIER_PIN), adminBearer())
                .getBody().get("id").asLong();
        return bearerFor(id, CASHIER_PIN);
    }

    private Long createStation(String name, String consoleType) {
        return post("/api/v1/stations", Map.of("name", name, "consoleType", consoleType), manager)
                .getBody().get("id").asLong();
    }

    private Long openShift() {
        return jdbc.queryForObject("INSERT INTO shifts (staff_id, terminal, opening_float) "
                + "VALUES (?, ?, 2000) RETURNING id", Long.class, adminId, TERMINAL);
    }

    private int countOf(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
