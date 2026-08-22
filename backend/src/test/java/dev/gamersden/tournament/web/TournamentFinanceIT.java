package dev.gamersden.tournament.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamersden.support.AbstractApiIntegrationTest;
import dev.gamersden.support.FloorFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /tournaments/{id}/finance} (docs/tournaments.md §6).
 *
 * <p>Two things matter and they are unrelated to each other.
 *
 * <p><strong>The numbers have to be the four formulas.</strong> Not an approximation of them, and
 * not read off anything stored: the panel is recomputed from the event row, the tickets still paid
 * for and the live rate card, so it moves when a manager edits the rate card and it does not move
 * when a bracket is drawn.
 *
 * <p><strong>A cashier must never see them.</strong> §6 is explicit — 403 for cashier tokens, and
 * never embedded in a shared payload. That second half is the one a role guard alone would miss,
 * so the shared reads are checked for leakage too.
 */
class TournamentFinanceIT extends AbstractApiIntegrationTest {

    private HttpHeaders manager;
    private HttpHeaders cashier;
    private Long ps5;
    private Long ps4;

    @BeforeEach
    void seed() {
        manager = adminBearer();
        ps5 = createStation("PS5-01", "PS5");
        ps4 = createStation("PS4-01", "PS4");
        new FloorFixtures(jdbc).openShift(adminId, TERMINAL);
        Long cashierId = post("/api/v1/staff",
                Map.of("name", "Rafi", "role", "CASHIER", "pin", "4321"), manager)
                .getBody().get("id").asLong();
        cashier = bearerFor(cashierId, "4321");
    }

    @Test
    @DisplayName("the four formulas, against the seeded rate card")
    void theFourFormulas() {
        // 8 slots at 500, prize 1500, 20-minute matches, one PS5 (120/h) and one PS4 (80/h).
        Long id = tournament(8, 500, 1500, 20);
        block(id, ps5, ps4);
        sell(id, 500, "Rifat", "Nafis", "Tanvir", "Shuvo", "Arif", "Sadi", "Jubair", "Mahin");

        JsonNode finance = get("/api/v1/tournaments/" + id + "/finance", manager).getBody();

        assertThat(finance.get("entries").asInt()).isEqualTo(8);
        assertThat(finance.get("revenue").asInt()).as("entries x entryFee").isEqualTo(4000);
        assertThat(finance.get("netProfit").asInt()).as("revenue - prizePool").isEqualTo(2500);
        assertThat(finance.get("matches").asInt()).as("N-1 for a cap of 8").isEqualTo(7);
        assertThat(finance.get("allocatedStations").asInt()).isEqualTo(2);
        assertThat(finance.get("avgHourlyRate").asInt()).as("mean of 120 and 80").isEqualTo(100);
        // 7 x 20/60 x 100 = 233.33... -> 233
        assertThat(finance.get("opportunityCost").asInt()).isEqualTo(233);
        assertThat(finance.get("extraMargin").asInt()).as("netProfit - opportunityCost")
                .isEqualTo(2267);
        assertThat(finance.get("verdict").asText())
                .isEqualTo("This tournament generates ৳2,267 extra compared to standard hourly "
                        + "rentals");
    }

    @Test
    @DisplayName("nothing is stored: the rate card moves the comparison, the draw does not")
    void everythingIsDerived() {
        Long id = tournament(4, 300, 0, 30);
        block(id, ps5);
        sell(id, 300, "Rifat", "Nafis", "Tanvir", "Shuvo");
        // The cap-filling sale drew the bracket; the event is LIVE and the numbers have not moved.
        // 3 x 30/60 x 120 = 180
        assertThat(financeOf(id).get("opportunityCost").asInt()).isEqualTo(180);
        assertThat(financeOf(id).get("extraMargin").asInt()).isEqualTo(1200 - 180);

        // An Admin raises the PS5 hourly rate: the comparison against standard rentals follows it.
        put("/api/v1/pricing/PS5", Map.of("perHour", 200), manager);

        assertThat(financeOf(id).get("avgHourlyRate").asInt()).isEqualTo(200);
        assertThat(financeOf(id).get("opportunityCost").asInt()).isEqualTo(300);
        assertThat(financeOf(id).get("extraMargin").asInt()).isEqualTo(900);
    }

    @Test
    @DisplayName("an event holding no consoles gives up nothing, and says so plainly")
    void noConsolesNoOpportunityCost() {
        Long id = tournament(4, 100, 800, 20);
        sell(id, 100, "Rifat", "Nafis");

        JsonNode finance = financeOf(id);

        assertThat(finance.get("allocatedStations").asInt()).isZero();
        assertThat(finance.get("opportunityCost").asInt()).isZero();
        assertThat(finance.get("netProfit").asInt()).isEqualTo(-600);
        assertThat(finance.get("extraMargin").asInt()).isEqualTo(-600);
        assertThat(finance.get("verdict").asText())
                .isEqualTo("This tournament earns ৳600 less than standard hourly rentals would "
                        + "have");
    }

    @Test
    @DisplayName("a cancelled event's refunded tickets stop counting as revenue")
    void refundsLeaveTheRevenue() {
        Long id = tournament(8, 500, 0, 20);
        block(id, ps5);
        sell(id, 500, "Rifat", "Nafis");
        assertThat(financeOf(id).get("revenue").asInt()).isEqualTo(1000);

        post("/api/v1/tournaments/" + id + "/cancel", Map.of("reason", "Power cut"), manager);

        assertThat(financeOf(id).get("entries").asInt()).isZero();
        assertThat(financeOf(id).get("revenue").asInt()).isZero();
    }

    // ---- the guard --------------------------------------------------------------------------

    @Test
    @DisplayName("finance is Manager+ — a cashier gets the 403 envelope")
    void cashierCannotReadFinance() {
        Long id = tournament(8, 500, 1500, 20);
        block(id, ps5);
        sell(id, 500, "Rifat");

        assertErrorEnvelope(get("/api/v1/tournaments/" + id + "/finance", cashier), 403,
                "FORBIDDEN");
    }

    @Test
    @DisplayName("and the numbers are in no payload a cashier can reach")
    void financeIsNeverEmbedded() {
        Long id = tournament(8, 500, 1500, 20);
        block(id, ps5);
        sell(id, 500, "Rifat");

        for (String path : List.of("/api/v1/tournaments", "/api/v1/tournaments/history",
                "/api/v1/tournaments/" + id, "/api/v1/tournaments/" + id + "/matches")) {
            String body = get(path, cashier).getBody().toString();
            assertThat(body).as("%s must not carry the finance panel", path)
                    .doesNotContain("netProfit")
                    .doesNotContain("opportunityCost")
                    .doesNotContain("extraMargin")
                    .doesNotContain("verdict");
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private JsonNode financeOf(Long id) {
        ResponseEntity<JsonNode> finance = get("/api/v1/tournaments/" + id + "/finance", manager);
        assertThat(finance.getStatusCode()).as("finance refused: %s", finance.getBody())
                .isEqualTo(HttpStatus.OK);
        return finance.getBody();
    }

    private Long tournament(int cap, int fee, int prize, int matchMinutes) {
        ResponseEntity<JsonNode> created = post("/api/v1/tournaments", Map.of(
                "name", "Friday FIFA", "game", "FIFA 25", "cadence", "WEEKLY",
                "scheduledAt", OffsetDateTime.now().plusDays(1).toString(),
                "entryFee", fee, "prizePool", prize, "maxPlayers", cap,
                "matchDurationMin", matchMinutes), manager);
        assertThat(created.getStatusCode()).as("create failed: %s", created.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return created.getBody().get("tournament").get("id").asLong();
    }

    private void block(Long tournamentId, Long... stationIds) {
        ResponseEntity<JsonNode> blocked = put("/api/v1/tournaments/" + tournamentId + "/blocks",
                Map.of("stationIds", List.of(stationIds)), manager);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void sell(Long tournamentId, int fee, String... players) {
        List<Map<String, Object>> lines = new ArrayList<>(players.length);
        for (String player : players) {
            lines.add(Map.of("tournamentId", tournamentId, "playerName", player));
        }
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(manager);
        headers.add("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<JsonNode> settled = post("/api/v1/payments", Map.of(
                "target", Map.of(),
                "tournamentEntries", lines,
                "splits", List.of(Map.of("method", "CASH", "amount", players.length * fee))),
                headers);
        assertThat(settled.getStatusCode()).as("settle failed: %s", settled.getBody())
                .isEqualTo(HttpStatus.CREATED);
    }

    private Long createStation(String name, String consoleType) {
        return post("/api/v1/stations", Map.of("name", name, "consoleType", consoleType), manager)
                .getBody().get("id").asLong();
    }
}
