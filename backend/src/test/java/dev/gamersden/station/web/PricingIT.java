package dev.gamersden.station.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamersden.station.domain.ConsoleType;
import dev.gamersden.station.domain.PricingService;
import dev.gamersden.support.AbstractApiIntegrationTest;
import dev.gamersden.support.FloorFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /pricing} — Admin writes, everyone reads, and the invariant the contract states in one
 * line: <em>new blocks only; running sessions keep purchased prices</em>.
 */
class PricingIT extends AbstractApiIntegrationTest {

    private static final String CASHIER_PIN = "4321";

    @Autowired
    private PricingService pricing;

    private FloorFixtures floor;

    @BeforeEach
    void seedFloorFixtures() {
        floor = new FloorFixtures(jdbc);
    }

    @Test
    void theSeededRateCardIsReadableByEveryOperator() {
        ResponseEntity<JsonNode> card = get("/api/v1/pricing", cashierBearer());

        assertThat(card.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(card.getBody()).hasSize(2);
        JsonNode ps5 = card.getBody().get(0);
        assertThat(ps5.get("consoleType").asText()).isEqualTo("PS5");
        assertThat(ps5.get("perHour").asInt()).isEqualTo(120);
        assertThat(ps5.get("perHalfHour").asInt()).isEqualTo(80);
        assertThat(ps5.get("morningDiscountPct").asInt()).isEqualTo(25);
        assertThat(ps5.get("morningStart").asText()).startsWith("10:00");
        assertThat(ps5.get("morningEnd").asText()).startsWith("14:00");
    }

    @Test
    void oneConsoleTypeReadsOnItsOwn() {
        ResponseEntity<JsonNode> ps4 = get("/api/v1/pricing/PS4", adminBearer());

        assertThat(ps4.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ps4.getBody().get("perHalfHour").asInt()).isEqualTo(50);
    }

    /** The done-when case: an Admin raises the rate mid-evening and no live bill moves. */
    @Test
    void aRateChangeReachesNewBlocksOnlyAndLeavesALiveSessionAlone() {
        Long stationId = createStation("PS5-01", "PS5");
        Long shift = floor.openShift(adminId, "T9");
        Long sessionId = floor.runningSessionOn(stationId, shift, 2, 80, 0);

        ResponseEntity<JsonNode> raised = put("/api/v1/pricing/PS5",
                Map.of("perHour", 150, "perHalfHour", 100, "morningDiscountPct", 0), adminBearer());

        assertThat(raised.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(raised.getBody().get("perHalfHour").asInt()).isEqualTo(100);
        // The next block sold costs the new rate...
        assertThat(raised.getBody().get("currentBlockPrice").asInt()).isEqualTo(100);
        assertThat(pricing.blockPrice(ConsoleType.PS5)).isEqualTo(100);
        // ...while the two blocks already on the session keep the price they were sold at.
        assertThat(floor.blockPricesOf(sessionId)).containsExactly(80, 80);

        JsonNode card = get("/api/v1/stations/" + stationId, adminBearer()).getBody();
        assertThat(card.get("session").get("blocks").asInt()).isEqualTo(2);
        assertThat(card.get("session").get("remainingSeconds").asLong()).isBetween(3540L, 3600L);
    }

    @Test
    void aBulkUpdateSetsEveryConsoleTypeAtOnce() {
        ResponseEntity<JsonNode> updated = put("/api/v1/pricing", List.of(
                Map.of("consoleType", "PS5", "perHour", 160, "perHalfHour", 90),
                Map.of("consoleType", "PS4", "perHour", 100, "perHalfHour", 60)), adminBearer());

        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody()).hasSize(2);
        assertThat(get("/api/v1/pricing/PS5", adminBearer()).getBody().get("perHalfHour").asInt())
                .isEqualTo(90);
        assertThat(get("/api/v1/pricing/PS4", adminBearer()).getBody().get("perHour").asInt())
                .isEqualTo(100);
    }

    @Test
    void omittedFieldsKeepTheirStoredValue() {
        String before = get("/api/v1/pricing/PS5", adminBearer()).getBody().get("updatedAt").asText();

        put("/api/v1/pricing/PS5", Map.of("perHalfHour", 90), adminBearer());

        JsonNode ps5 = get("/api/v1/pricing/PS5", adminBearer()).getBody();
        assertThat(ps5.get("perHalfHour").asInt()).isEqualTo(90);
        assertThat(ps5.get("perHour").asInt()).isEqualTo(120);
        assertThat(ps5.get("morningDiscountPct").asInt()).isEqualTo(25);
        // S10 shows when the card last moved — the stamp has to actually move with it.
        assertThat(ps5.get("updatedAt").asText()).isNotEqualTo(before);
    }

    @Test
    void theMorningWindowIsEditableAndMustEndAfterItStarts() {
        ResponseEntity<JsonNode> moved = put("/api/v1/pricing/PS5",
                Map.of("morningStart", "09:00", "morningEnd", "13:00", "morningDiscountPct", 20),
                adminBearer());

        assertThat(moved.getBody().get("morningStart").asText()).startsWith("09:00");
        assertThat(moved.getBody().get("morningDiscountPct").asInt()).isEqualTo(20);

        assertErrorEnvelope(put("/api/v1/pricing/PS5",
                Map.of("morningStart", "14:00", "morningEnd", "10:00"), adminBearer()),
                400, "VALIDATION_FAILED");
    }

    @Test
    void nonsenseRatesAreRejected() {
        assertErrorEnvelope(put("/api/v1/pricing/PS5", Map.of("perHalfHour", 0), adminBearer()),
                400, "VALIDATION_FAILED");
        assertErrorEnvelope(put("/api/v1/pricing/PS5", Map.of("morningDiscountPct", 120), adminBearer()),
                400, "VALIDATION_FAILED");
        assertErrorEnvelope(put("/api/v1/pricing/PS5",
                Map.of("consoleType", "PS4", "perHalfHour", 90), adminBearer()),
                400, "VALIDATION_FAILED");
        assertErrorEnvelope(put("/api/v1/pricing",
                List.of(Map.of("perHalfHour", 90)), adminBearer()), 400, "VALIDATION_FAILED");
        assertErrorEnvelope(get("/api/v1/pricing/PS3", adminBearer()), 400, "VALIDATION_FAILED");
    }

    @Test
    void aCashierReadsTheRateCardButCannotWriteIt() {
        HttpHeaders cashier = cashierBearer();

        assertThat(get("/api/v1/pricing", cashier).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertErrorEnvelope(put("/api/v1/pricing/PS5", Map.of("perHalfHour", 10), cashier),
                403, "FORBIDDEN");
        assertErrorEnvelope(put("/api/v1/pricing",
                List.of(Map.of("consoleType", "PS5", "perHalfHour", 10)), cashier), 403, "FORBIDDEN");
        assertThat(get("/api/v1/pricing/PS5", adminBearer()).getBody().get("perHalfHour").asInt())
                .isEqualTo(80);
    }

    @Test
    void aManagerIsAlsoBelowThePricingBar() {
        ResponseEntity<JsonNode> created = post("/api/v1/staff",
                Map.of("name", "Tanvir", "role", "MANAGER", "pin", "2222"), adminBearer());
        HttpHeaders manager = bearerFor(created.getBody().get("id").asLong(), "2222");

        assertErrorEnvelope(put("/api/v1/pricing/PS5", Map.of("perHalfHour", 10), manager),
                403, "FORBIDDEN");
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
