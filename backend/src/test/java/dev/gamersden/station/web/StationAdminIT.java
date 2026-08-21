package dev.gamersden.station.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamersden.support.AbstractApiIntegrationTest;
import dev.gamersden.support.FloorFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /stations} — the Floor read every operator gets, and the Admin-only writes with the two
 * 409s the contract names: {@code DUPLICATE_NAME} and {@code STATION_IN_USE}.
 */
class StationAdminIT extends AbstractApiIntegrationTest {

    private static final String CASHIER_PIN = "4321";

    private FloorFixtures floor;

    @BeforeEach
    void seedFloorFixtures() {
        floor = new FloorFixtures(jdbc);
    }

    @Test
    void adminAddsAStationAndItLandsOnTheFloorAsFree() {
        ResponseEntity<JsonNode> created = post("/api/v1/stations",
                Map.of("name", "PS5-01", "consoleType", "PS5"), adminBearer());

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("name").asText()).isEqualTo("PS5-01");
        assertThat(created.getBody().get("consoleType").asText()).isEqualTo("PS5");
        assertThat(created.getBody().get("status").asText()).isEqualTo("AVAILABLE");
        assertThat(created.getBody().get("floorState").asText()).isEqualTo("FREE");
        // non_null inclusion: a free seat carries no session, match or arrival at all.
        assertThat(created.getBody().has("session")).isFalse();
        assertThat(created.getBody().has("match")).isFalse();
        assertThat(created.getBody().has("arrival")).isFalse();

        ResponseEntity<JsonNode> floorGrid = get("/api/v1/stations", adminBearer());
        assertThat(floorGrid.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(floorGrid.getBody()).hasSize(1);
    }

    @Test
    void aDuplicateNameIs409DuplicateName() {
        createStation("PS5-01", "PS5");

        ResponseEntity<JsonNode> again = post("/api/v1/stations",
                Map.of("name", "PS5-01", "consoleType", "PS4"), adminBearer());

        assertErrorEnvelope(again, 409, "DUPLICATE_NAME");
        assertThat(get("/api/v1/stations", adminBearer()).getBody()).hasSize(1);
    }

    @Test
    void renamingOntoATakenNameIs409DuplicateName() {
        createStation("PS5-01", "PS5");
        Long other = createStation("PS5-02", "PS5");

        assertErrorEnvelope(patch("/api/v1/stations/" + other, Map.of("name", "PS5-01"), adminBearer()),
                409, "DUPLICATE_NAME");
    }

    @Test
    void renamingAStationKeepsItsIdAndCard() {
        Long id = createStation("PS5-01", "PS5");

        ResponseEntity<JsonNode> patched =
                patch("/api/v1/stations/" + id, Map.of("name", "Bay 1"), adminBearer());

        assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patched.getBody().get("id").asLong()).isEqualTo(id);
        assertThat(patched.getBody().get("name").asText()).isEqualTo("Bay 1");
    }

    @Test
    void theFloorCardCarriesTheLiveSessionSummary() {
        Long id = createStation("PS5-01", "PS5");
        Long shift = floor.openShift(adminId, "T9");
        // Two blocks bought (3600 s), ten minutes already burned, clock running.
        Long sessionId = floor.runningSessionOn(id, shift, 2, 80, 600);

        JsonNode card = get("/api/v1/stations", adminBearer()).getBody().get(0);

        assertThat(card.get("floorState").asText()).isEqualTo("RUNNING");
        JsonNode session = card.get("session");
        assertThat(session.get("id").asLong()).isEqualTo(sessionId);
        assertThat(session.get("state").asText()).isEqualTo("RUNNING");
        assertThat(session.get("blocks").asInt()).isEqualTo(2);
        assertThat(session.get("paidBlocks").asInt()).isZero();
        // 2 x 1800 - 600 consumed - the sliver since running_since.
        assertThat(session.get("remainingSeconds").asLong()).isBetween(2940L, 3000L);
    }

    @Test
    void deletingAStationWithALiveSessionIs409StationInUse() {
        Long id = createStation("PS5-01", "PS5");
        Long shift = floor.openShift(adminId, "T9");
        floor.runningSessionOn(id, shift, 1, 80, 0);

        assertErrorEnvelope(delete("/api/v1/stations/" + id, adminBearer()), 409, "STATION_IN_USE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stations WHERE id = ?", Integer.class, id))
                .isEqualTo(1);
    }

    @Test
    void deletingAStationWithClosedSessionHistoryIs409StationInUse() {
        Long id = createStation("PS5-01", "PS5");
        Long shift = floor.openShift(adminId, "T9");
        floor.closedSessionOn(id, shift);

        assertErrorEnvelope(delete("/api/v1/stations/" + id, adminBearer()), 409, "STATION_IN_USE");
    }

    @Test
    void deletingAStationNothingPointsAtRemovesIt() {
        Long id = createStation("PS5-01", "PS5");

        assertThat(delete("/api/v1/stations/" + id, adminBearer()).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(get("/api/v1/stations", adminBearer()).getBody()).isEmpty();
    }

    @Test
    void takingABusySeatOffTheFloorIs409StationInUse() {
        Long id = createStation("PS5-01", "PS5");
        Long shift = floor.openShift(adminId, "T9");
        floor.runningSessionOn(id, shift, 1, 80, 0);

        assertErrorEnvelope(patch("/api/v1/stations/" + id, Map.of("status", "MAINTENANCE"), adminBearer()),
                409, "STATION_IN_USE");
        assertErrorEnvelope(patch("/api/v1/stations/" + id, Map.of("consoleType", "PS4"), adminBearer()),
                409, "STATION_IN_USE");
    }

    @Test
    void aFreeSeatCanGoUnderMaintenanceAndComeBack() {
        Long id = createStation("PS5-01", "PS5");

        ResponseEntity<JsonNode> down =
                patch("/api/v1/stations/" + id, Map.of("status", "MAINTENANCE"), adminBearer());
        assertThat(down.getBody().get("floorState").asText()).isEqualTo("MAINTENANCE");

        ResponseEntity<JsonNode> up =
                patch("/api/v1/stations/" + id, Map.of("status", "AVAILABLE"), adminBearer());
        assertThat(up.getBody().get("floorState").asText()).isEqualTo("FREE");
    }

    @Test
    void aCashierReadsTheFloorButCannotWriteIt() {
        Long id = createStation("PS5-01", "PS5");
        HttpHeaders cashier = cashierBearer();

        assertThat(get("/api/v1/stations", cashier).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/v1/stations/" + id, cashier).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertErrorEnvelope(post("/api/v1/stations",
                Map.of("name", "PS4-01", "consoleType", "PS4"), cashier), 403, "FORBIDDEN");
        assertErrorEnvelope(patch("/api/v1/stations/" + id, Map.of("name", "Bay 1"), cashier),
                403, "FORBIDDEN");
        assertErrorEnvelope(delete("/api/v1/stations/" + id, cashier), 403, "FORBIDDEN");
    }

    @Test
    void aManagerIsAlsoBelowTheStationsBar() {
        ResponseEntity<JsonNode> created = post("/api/v1/staff",
                Map.of("name", "Tanvir", "role", "MANAGER", "pin", "2222"), adminBearer());
        HttpHeaders manager = bearerFor(created.getBody().get("id").asLong(), "2222");

        assertErrorEnvelope(post("/api/v1/stations",
                Map.of("name", "PS4-01", "consoleType", "PS4"), manager), 403, "FORBIDDEN");
        assertThat(get("/api/v1/stations", manager).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anUnknownStationIs404() {
        assertErrorEnvelope(get("/api/v1/stations/999999", adminBearer()), 404, "NOT_FOUND");
        assertErrorEnvelope(delete("/api/v1/stations/999999", adminBearer()), 404, "NOT_FOUND");
    }

    @Test
    void aBlankNameOrUnknownConsoleTypeIs400() {
        assertErrorEnvelope(post("/api/v1/stations",
                Map.of("name", "  ", "consoleType", "PS5"), adminBearer()), 400, "VALIDATION_FAILED");
        assertErrorEnvelope(post("/api/v1/stations",
                Map.of("name", "PS3-01", "consoleType", "PS3"), adminBearer()), 400, "VALIDATION_FAILED");
    }

    @Test
    void noTokenAtAllIs401NotForbidden() {
        assertErrorEnvelope(get("/api/v1/stations", null), 401, "UNAUTHORIZED");
    }

    private Long createStation(String name, String consoleType) {
        ResponseEntity<JsonNode> created = post("/api/v1/stations",
                Map.of("name", name, "consoleType", consoleType), adminBearer());
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody().get("id").asLong();
    }

    private HttpHeaders cashierBearer() {
        ResponseEntity<JsonNode> created = post("/api/v1/staff",
                Map.of("name", "Rafi", "role", "CASHIER", "pin", CASHIER_PIN), adminBearer());
        return bearerFor(created.getBody().get("id").asLong(), CASHIER_PIN);
    }
}
