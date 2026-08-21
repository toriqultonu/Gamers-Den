package dev.gamersden.session.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamersden.common.config.VenueTime;
import dev.gamersden.support.AbstractApiIntegrationTest;
import dev.gamersden.support.FloorFixtures;
import dev.gamersden.support.MutableClock;
import dev.gamersden.support.MutableClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a block costs is decided once, when it is bought, and written to
 * {@code session_blocks.price} — the rate card can move afterwards and the sold block does not
 * (api-contract.md, "Stations &amp; pricing": new blocks only).
 *
 * <p>Two ways that matters on a real evening: the morning window closing between two taps of
 * {@code +30}, and an Admin editing the rate card mid-session. Both are asserted against the rows
 * Postgres actually holds, not just the response body.
 *
 * <p>The morning window (10:00-14:00, -25%) is the documented default for the OPEN FLAG in
 * ARCHITECTURE.md §8 — still unconfirmed by the venue.
 */
@Import(MutableClockConfig.class)
class SessionBlockPricingIT extends AbstractApiIntegrationTest {

    @Autowired
    private MutableClock clock;

    private FloorFixtures floor;
    private HttpHeaders staff;
    private Long stationId;
    private long sessionId;

    @BeforeEach
    void seedFloor() {
        jdbc.update("DELETE FROM idempotency_keys");
        clock.resetToNow();
        floor = new FloorFixtures(jdbc);
        staff = adminBearer();
        stationId = createStation();
        floor.openShift(adminId, TERMINAL);
    }

    @Test
    @DisplayName("the block bought at 13:59 is discounted, the one at 14:01 is not")
    void theMorningWindowClosesBetweenTwoBlocks() {
        atVenueTime(LocalTime.of(13, 59, 0));
        openSession();

        buyBlock();
        // The window is half-open: 14:00 is already the standard rate.
        atVenueTime(LocalTime.of(14, 1, 0));
        buyBlock();

        assertThat(floor.blockPricesOf(sessionId)).containsExactly(60, 80);
        assertThat(session().get("gamingDue").asInt()).isEqualTo(140);
    }

    @Test
    @DisplayName("the last second inside the window still buys the morning rate")
    void theBoundarySecondIsInclusiveOfTheDiscount() {
        atVenueTime(LocalTime.of(13, 59, 59));
        openSession();
        buyBlock();

        clock.advance(Duration.ofSeconds(1));
        staff = adminBearer();
        buyBlock();

        // 13:59:59 -> discounted, 14:00:00 -> full rate. One second apart.
        assertThat(floor.blockPricesOf(sessionId)).containsExactly(60, 80);
    }

    @Test
    @DisplayName("the window opens on the hour: 09:59:59 pays full, 10:00:00 pays morning")
    void theWindowOpensOnTheHour() {
        atVenueTime(LocalTime.of(9, 59, 59));
        openSession();
        buyBlock();

        clock.advance(Duration.ofSeconds(1));
        staff = adminBearer();
        buyBlock();

        assertThat(floor.blockPricesOf(sessionId)).containsExactly(80, 60);
    }

    @Test
    @DisplayName("a rate edit mid-session moves the next block and never a sold one")
    void aRateEditNeverRewritesHistory() {
        atVenueTime(LocalTime.of(20, 0, 0));
        openSession();
        buyBlock();

        ResponseEntity<JsonNode> repriced = put("/api/v1/pricing/PS5",
                Map.of("perHour", 220, "perHalfHour", 150), adminBearer());
        assertThat(repriced.getStatusCode()).isEqualTo(HttpStatus.OK);

        buyBlock();

        assertThat(floor.blockPricesOf(sessionId)).containsExactly(80, 150);
        assertThat(session().get("gamingDue").asInt()).isEqualTo(230);
    }

    @Test
    @DisplayName("a returned block takes its own price off the bill, not the newest price")
    void returningABlockRemovesTheBlockThatWasReturned() {
        atVenueTime(LocalTime.of(13, 59, 0));
        openSession();
        buyBlock();
        atVenueTime(LocalTime.of(14, 1, 0));
        buyBlock();

        post("/api/v1/sessions/" + sessionId + "/blocks", Map.of("delta", -1), keyed());

        // The newest unpaid block goes first: the 80 leaves, the discounted 60 stays.
        assertThat(floor.blockPricesOf(sessionId)).containsExactly(60);
        assertThat(session().get("gamingDue").asInt()).isEqualTo(60);
    }

    @Test
    @DisplayName("each console type is priced off its own row")
    void aPs4BlockIsPricedFromThePs4Card() {
        atVenueTime(LocalTime.of(20, 0, 0));
        Long ps4 = createStation("PS4-01", "PS4");
        long ps4Session = post("/api/v1/sessions", Map.of("stationId", ps4), staff)
                .getBody().get("id").asLong();

        post("/api/v1/sessions/" + ps4Session + "/blocks", Map.of("delta", 1), keyed());

        // Seeded card: PS5 80 a block, PS4 50.
        assertThat(floor.blockPricesOf(ps4Session)).containsExactly(50);
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** Parks the venue clock and re-issues the token, which expires against that same clock. */
    private void atVenueTime(LocalTime timeOfDay) {
        clock.setToVenueTime(LocalDate.now(VenueTime.ZONE), timeOfDay);
        staff = adminBearer();
    }

    private void openSession() {
        ResponseEntity<JsonNode> created =
                post("/api/v1/sessions", Map.of("stationId", stationId), staff);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        sessionId = created.getBody().get("id").asLong();
    }

    private void buyBlock() {
        ResponseEntity<JsonNode> bought =
                post("/api/v1/sessions/" + sessionId + "/blocks", Map.of("delta", 1), keyed());
        assertThat(bought.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private JsonNode session() {
        return get("/api/v1/sessions/" + sessionId, staff).getBody();
    }

    private HttpHeaders keyed() {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(staff);
        headers.add("Idempotency-Key", UUID.randomUUID().toString());
        return headers;
    }

    private Long createStation() {
        return createStation("PS5-01", "PS5");
    }

    private Long createStation(String name, String consoleType) {
        ResponseEntity<JsonNode> created = post("/api/v1/stations",
                Map.of("name", name, "consoleType", consoleType), adminBearer());
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody().get("id").asLong();
    }
}
