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
 * {@code POST /tournament-entries/{id}/check-in} — the QR off the P5 stub, scanned at the door
 * (docs/tournaments.md §7).
 */
class TournamentCheckInIT extends AbstractApiIntegrationTest {

    private static final int FEE = 200;

    private TournamentFixtures fixtures;
    private HttpHeaders staff;
    private Long tournamentId;
    private long entryId;
    private String qrToken;

    @BeforeEach
    void seed() {
        fixtures = new TournamentFixtures(jdbc);
        staff = adminBearer();
        new FloorFixtures(jdbc).openShift(adminId, TERMINAL);
        tournamentId = fixtures.openTournament("Friday FIFA", FEE, 8, adminId);

        JsonNode sold = post("/api/v1/tournaments/" + tournamentId + "/entries",
                Map.of("playerName", "Rifat Hasan",
                        "splits", List.of(Map.of("method", "CASH", "amount", FEE))),
                withKey()).getBody();
        entryId = sold.get("entryId").asLong();
        qrToken = sold.get("qrToken").asText();
    }

    @Test
    @DisplayName("the first scan marks the player as arrived")
    void firstScanChecksIn() {
        ResponseEntity<JsonNode> checked = post("/api/v1/tournament-entries/" + entryId + "/check-in",
                Map.of("qrToken", qrToken), staff);

        assertThat(checked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(checked.getBody().get("checkedIn").asBoolean()).isTrue();
        assertThat(checked.getBody().get("playerName").asText()).isEqualTo("Rifat Hasan");
        // The token is never handed back on a read — it is the ticket.
        assertThat(checked.getBody().has("qrToken")).isFalse();
        assertThat(fixtures.entriesOf(tournamentId).get(0)).containsEntry("checked_in", true);
    }

    @Test
    @DisplayName("a second scan is 409 ALREADY_CHECKED_IN")
    void secondScanIsRefused() {
        post("/api/v1/tournament-entries/" + entryId + "/check-in", Map.of("qrToken", qrToken), staff);

        assertErrorEnvelope(post("/api/v1/tournament-entries/" + entryId + "/check-in",
                Map.of("qrToken", qrToken), staff), 409, "ALREADY_CHECKED_IN");
    }

    @Test
    @DisplayName("a QR that belongs to another entry does not open the door")
    void theTokenHasToMatchTheEntry() {
        assertErrorEnvelope(post("/api/v1/tournament-entries/" + entryId + "/check-in",
                Map.of("qrToken", "deadbeefdeadbeefdeadbeefdeadbeef"), staff),
                400, "VALIDATION_FAILED");
        assertThat(fixtures.entriesOf(tournamentId).get(0)).containsEntry("checked_in", false);
    }

    @Test
    @DisplayName("an unknown entry is 404")
    void unknownEntry() {
        assertErrorEnvelope(post("/api/v1/tournament-entries/9999/check-in",
                Map.of("qrToken", qrToken), staff), 404, "NOT_FOUND");
    }

    @Test
    @DisplayName("a refunded ticket no longer works")
    void refundedTicketsAreDead() {
        post("/api/v1/tournaments/" + tournamentId + "/cancel", Map.of("reason", "Called off"), staff);

        assertErrorEnvelope(post("/api/v1/tournament-entries/" + entryId + "/check-in",
                Map.of("qrToken", qrToken), staff), 409, "CONFLICT");
    }

    private HttpHeaders withKey() {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(staff);
        headers.add("Idempotency-Key", UUID.randomUUID().toString());
        return headers;
    }
}
