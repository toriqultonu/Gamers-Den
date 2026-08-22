package dev.gamersden.tournament.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamersden.support.AbstractApiIntegrationTest;
import dev.gamersden.support.FloorFixtures;
import dev.gamersden.support.TournamentFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Selling entries (docs/tournaments.md §5) — the release-critical half of B12.
 *
 * <p>What is proved here is that a registration and its money are the same event. One settle
 * writes the transaction with its {@code tournament_amount}, the entry with its seed and QR, and
 * the receipt carrying the P5 stub; a refusal writes none of the three; and a retry under the same
 * {@code Idempotency-Key} charges once and registers once (invariants §5.2, §5.3, §5.7).
 */
class TournamentEntrySaleIT extends AbstractApiIntegrationTest {

    private static final int PS5_HALF_HOUR = 80;
    private static final int FEE = 200;

    private FloorFixtures floor;
    private TournamentFixtures fixtures;
    private HttpHeaders staff;
    private Long stationId;
    private Long shiftId;
    private Long tournamentId;

    @BeforeEach
    void seed() {
        floor = new FloorFixtures(jdbc);
        fixtures = new TournamentFixtures(jdbc);
        staff = adminBearer();
        stationId = createStation("PS5-01", "PS5");
        shiftId = floor.openShift(adminId, TERMINAL);
        tournamentId = fixtures.openTournament("Friday FIFA", FEE, 4, adminId);
    }

    // ---- the whole footprint of one entry sale ---------------------------------------------------

    @Test
    @DisplayName("an entry sold with a seat's bill writes the money, the seed, the token and the stub")
    void entrySoldThroughSettle() {
        Long sessionId = floor.runningSessionOn(stationId, shiftId, 2, PS5_HALF_HOUR, 0);

        // 2 blocks at 80 plus one 200 entry = 360.
        JsonNode settled = settle(Map.of(
                "target", Map.of("sessionId", sessionId),
                "tournamentEntries", List.of(
                        Map.of("tournamentId", tournamentId, "playerName", "Rifat Hasan")),
                "splits", List.of(Map.of("method", "CASH", "amount", 360))));

        long txId = settled.get("transactionId").asLong();
        assertThat(settled.get("entryTokens")).hasSize(1);
        String token = settled.get("entryTokens").get(0).asText();
        assertThat(token).hasSize(32).matches("[0-9a-f]+");

        // The takings split the entry out into its own bucket — that is the X/Z tournament line.
        Map<String, Object> tx = jdbc.queryForMap("SELECT gaming_amount, fnb_amount, "
                + "tournament_amount, booking_amount, total_due, session_id FROM transactions "
                + "WHERE id = ?", txId);
        assertThat(tx).containsEntry("gaming_amount", 160)
                .containsEntry("fnb_amount", 0)
                .containsEntry("tournament_amount", FEE)
                .containsEntry("booking_amount", 0)
                .containsEntry("total_due", 360)
                .containsEntry("session_id", sessionId);

        List<Map<String, Object>> entries = fixtures.entriesOf(tournamentId);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0)).containsEntry("seed", 1)
                .containsEntry("player_name", "Rifat Hasan")
                .containsEntry("tx_id", txId)
                .containsEntry("qr_token", token)
                .containsEntry("checked_in", false)
                .containsEntry("refunded", false);

        // The P5 stub rode along on the sale's own print job (docs/tournaments.md §7).
        String paper = jdbc.queryForObject("SELECT rendered_text FROM print_jobs WHERE id = ?",
                String.class, settled.get("printJobId").asLong());
        assertThat(paper).contains("TOURNAMENT ENTRY")
                .contains("Friday FIFA")
                .contains("Rifat Hasan")
                .contains("TOKEN #01")
                .contains(token);
        assertThat(countOf("print_jobs")).isEqualTo(1);
    }

    @Test
    @DisplayName("several entries in one sale take consecutive seeds and one token each")
    void manyEntriesOneSale() {
        JsonNode settled = settle(Map.of(
                "target", Map.of(),
                "tournamentEntries", List.of(
                        Map.of("tournamentId", tournamentId, "playerName", "Rifat"),
                        Map.of("tournamentId", tournamentId, "playerName", "Nafis"),
                        Map.of("tournamentId", tournamentId, "playerName", "Tanvir")),
                "splits", List.of(Map.of("method", "CASH", "amount", 3 * FEE))));

        assertThat(settled.get("entryTokens")).hasSize(3);
        List<Map<String, Object>> entries = fixtures.entriesOf(tournamentId);
        assertThat(entries).extracting(row -> row.get("seed")).containsExactly(1, 2, 3);
        assertThat(entries).extracting(row -> row.get("player_name"))
                .containsExactly("Rifat", "Nafis", "Tanvir");
        assertThat(entries).extracting(row -> row.get("qr_token")).doesNotHaveDuplicates();
        assertThat(jdbc.queryForObject("SELECT tournament_amount FROM transactions WHERE id = ?",
                Integer.class, settled.get("transactionId").asLong())).isEqualTo(3 * FEE);
    }

    @Test
    @DisplayName("a walk-up buying only a ticket needs no seat and no cart")
    void entryOnlySale() {
        JsonNode settled = settle(Map.of(
                "target", Map.of(),
                "tournamentEntries", List.of(Map.of("tournamentId", tournamentId)),
                "splits", List.of(Map.of("method", "CASH", "amount", FEE))));

        Map<String, Object> tx = jdbc.queryForMap("SELECT session_id, cart_id, tournament_amount, "
                + "total_due FROM transactions WHERE id = ?", settled.get("transactionId").asLong());
        assertThat(tx.get("session_id")).isNull();
        assertThat(tx.get("cart_id")).isNull();
        assertThat(tx).containsEntry("tournament_amount", FEE).containsEntry("total_due", FEE);
        // No name typed, no member on the sale: the stub says who it is for anyway.
        assertThat(fixtures.entriesOf(tournamentId).get(0))
                .containsEntry("player_name", "Walk-in guest");
    }

    @Test
    @DisplayName("the member on the seat names the ticket and gets the entry")
    void memberOnTheBillFillsTheStub() {
        Long memberId = createMember("Rifat Hasan", "+8801712448190");
        Long sessionId = floor.runningSessionOn(stationId, shiftId, 1, PS5_HALF_HOUR, 0);
        jdbc.update("UPDATE sessions SET member_id = ? WHERE id = ?", memberId, sessionId);

        settle(Map.of("target", Map.of("sessionId", sessionId),
                "tournamentEntries", List.of(Map.of("tournamentId", tournamentId)),
                "splits", List.of(Map.of("method", "CASH", "amount", PS5_HALF_HOUR + FEE))));

        assertThat(fixtures.entriesOf(tournamentId).get(0))
                .containsEntry("player_name", "Rifat Hasan")
                .containsEntry("member_id", memberId);
    }

    // ---- the counter route -----------------------------------------------------------------------

    @Test
    @DisplayName("POST /tournaments/{id}/entries sells the same thing in one call")
    void counterSale() {
        ResponseEntity<JsonNode> sold = post("/api/v1/tournaments/" + tournamentId + "/entries",
                Map.of("playerName", "Nafis Iqbal",
                        "splits", List.of(Map.of("method", "CASH", "amount", FEE))),
                withKey(UUID.randomUUID().toString()));

        assertThat(sold.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = sold.getBody();
        assertThat(body.get("seed").asInt()).isEqualTo(1);
        assertThat(body.get("qrToken").asText()).hasSize(32);

        List<Map<String, Object>> entries = fixtures.entriesOf(tournamentId);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0)).containsEntry("id", body.get("entryId").asLong())
                .containsEntry("tx_id", body.get("transactionId").asLong())
                .containsEntry("qr_token", body.get("qrToken").asText());
        assertThat(jdbc.queryForObject("SELECT tournament_amount FROM transactions WHERE id = ?",
                Integer.class, body.get("transactionId").asLong())).isEqualTo(FEE);
    }

    @Test
    @DisplayName("a cashier may sell an entry — that is execution, not configuration")
    void cashierMaySell() {
        Long cashierId = post("/api/v1/staff",
                Map.of("name", "Rafi", "role", "CASHIER", "pin", "4321"), staff)
                .getBody().get("id").asLong();
        HttpHeaders cashier = bearerFor(cashierId, "4321");
        HttpHeaders keyed = new HttpHeaders();
        keyed.addAll(cashier);
        keyed.add("Idempotency-Key", UUID.randomUUID().toString());

        ResponseEntity<JsonNode> sold = post("/api/v1/tournaments/" + tournamentId + "/entries",
                Map.of("splits", List.of(Map.of("method", "CASH", "amount", FEE))), keyed);

        assertThat(sold.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(fixtures.entriesOf(tournamentId)).hasSize(1);
    }

    // ---- refusals leave nothing behind ------------------------------------------------------------

    @Test
    @DisplayName("the sale past the cap is 409 TOURNAMENT_FULL and registers nobody")
    void fullIsRefused() {
        for (int i = 0; i < 4; i++) {
            settle(Map.of("target", Map.of(),
                    "tournamentEntries", List.of(Map.of("tournamentId", tournamentId)),
                    "splits", List.of(Map.of("method", "CASH", "amount", FEE))));
        }
        assertThat(fixtures.entriesOf(tournamentId)).hasSize(4);
        int before = countOf("transactions");

        ResponseEntity<JsonNode> fifth = post("/api/v1/payments",
                Map.of("target", Map.of(),
                        "tournamentEntries", List.of(Map.of("tournamentId", tournamentId)),
                        "splits", List.of(Map.of("method", "CASH", "amount", FEE))),
                withKey(UUID.randomUUID().toString()));

        assertErrorEnvelope(fifth, 409, "TOURNAMENT_FULL");
        assertThat(fixtures.entriesOf(tournamentId)).hasSize(4);
        assertThat(countOf("transactions")).isEqualTo(before);
        assertThat(countOf("print_jobs")).isEqualTo(before);
    }

    @Test
    @DisplayName("a batch that overruns the cap is refused whole — the first entries are not written")
    void aBatchIsAllOrNothing() {
        ResponseEntity<JsonNode> tooMany = post("/api/v1/payments",
                Map.of("target", Map.of(),
                        "tournamentEntries", List.of(
                                Map.of("tournamentId", tournamentId),
                                Map.of("tournamentId", tournamentId),
                                Map.of("tournamentId", tournamentId),
                                Map.of("tournamentId", tournamentId),
                                Map.of("tournamentId", tournamentId)),
                        "splits", List.of(Map.of("method", "CASH", "amount", 5 * FEE))),
                withKey(UUID.randomUUID().toString()));

        assertErrorEnvelope(tooMany, 409, "TOURNAMENT_FULL");
        assertThat(fixtures.entriesOf(tournamentId)).isEmpty();
        assertThat(countOf("transactions")).isZero();
    }

    @Test
    @DisplayName("an event that is not OPEN sells nothing")
    void notOpenIsRefused() {
        fixtures.setStatus(tournamentId, "LIVE");

        assertErrorEnvelope(post("/api/v1/payments",
                Map.of("target", Map.of(),
                        "tournamentEntries", List.of(Map.of("tournamentId", tournamentId)),
                        "splits", List.of(Map.of("method", "CASH", "amount", FEE))),
                withKey(UUID.randomUUID().toString())), 409, "TOURNAMENT_NOT_OPEN");

        fixtures.setStatus(tournamentId, "CANCELLED");
        assertErrorEnvelope(post("/api/v1/tournaments/" + tournamentId + "/entries",
                Map.of("splits", List.of(Map.of("method", "CASH", "amount", FEE))),
                withKey(UUID.randomUUID().toString())), 409, "TOURNAMENT_NOT_OPEN");

        assertThat(countOf("tournament_entries")).isZero();
        assertThat(countOf("transactions")).isZero();
    }

    @Test
    @DisplayName("tenders that do not cover the entry are 409 SPLIT_MISMATCH and register nobody")
    void splitMustCoverTheFee() {
        ResponseEntity<JsonNode> short_ = post("/api/v1/payments",
                Map.of("target", Map.of(),
                        "tournamentEntries", List.of(Map.of("tournamentId", tournamentId)),
                        "splits", List.of(Map.of("method", "CASH", "amount", 100))),
                withKey(UUID.randomUUID().toString()));

        assertErrorEnvelope(short_, 409, "SPLIT_MISMATCH");
        assertThat(short_.getBody().get("error").get("details").get("expected").asInt())
                .isEqualTo(FEE);
        assertThat(countOf("tournament_entries")).isZero();
        assertThat(countOf("transactions")).isZero();
    }

    // ---- idempotency ---------------------------------------------------------------------------------

    @Test
    @DisplayName("a replayed entry sale returns the same token and registers the player once")
    void replayRegistersOnce() {
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("target", Map.of(),
                "tournamentEntries", List.of(Map.of("tournamentId", tournamentId,
                        "playerName", "Rifat")),
                "splits", List.of(Map.of("method", "CASH", "amount", FEE)));

        ResponseEntity<JsonNode> first = post("/api/v1/payments", body, withKey(key));
        ResponseEntity<JsonNode> replay = post("/api/v1/payments", body, withKey(key));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(fixtures.entriesOf(tournamentId)).hasSize(1);
        assertThat(countOf("transactions")).isEqualTo(1);
        assertThat(countOf("print_jobs")).isEqualTo(1);
    }

    @Test
    @DisplayName("the counter route is on the guarded list too")
    void counterSaleNeedsAKey() {
        ResponseEntity<JsonNode> response = post("/api/v1/tournaments/" + tournamentId + "/entries",
                Map.of("splits", List.of(Map.of("method", "CASH", "amount", FEE))), staff);

        assertErrorEnvelope(response, 400, "VALIDATION_FAILED");
        assertThat(countOf("tournament_entries")).isZero();
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    private JsonNode settle(Map<String, Object> body) {
        ResponseEntity<JsonNode> response =
                post("/api/v1/payments", body, withKey(UUID.randomUUID().toString()));
        assertThat(response.getStatusCode()).as("settle failed: %s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private HttpHeaders withKey(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(staff);
        headers.add("Idempotency-Key", key);
        return headers;
    }

    private Long createStation(String name, String consoleType) {
        return post("/api/v1/stations", Map.of("name", name, "consoleType", consoleType), staff)
                .getBody().get("id").asLong();
    }

    private Long createMember(String name, String phone) {
        return jdbc.queryForObject("INSERT INTO members (name, phone) VALUES (?, ?) RETURNING id",
                Long.class, name, phone);
    }

    private int countOf(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
