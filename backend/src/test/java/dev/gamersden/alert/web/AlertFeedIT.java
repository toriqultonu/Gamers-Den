package dev.gamersden.alert.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamersden.printing.domain.FakePrinterPort;
import dev.gamersden.printing.domain.FakePrinterPortProvider;
import dev.gamersden.printing.domain.PrintQueueWorker;
import dev.gamersden.printing.domain.PrinterStatus;
import dev.gamersden.support.AbstractApiIntegrationTest;
import dev.gamersden.support.FloorFixtures;
import dev.gamersden.support.SseClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The operator feed: {@code /alerts} and the SSE {@code alert} event (B19).
 *
 * <p>The three kinds are raised the way they are raised in the venue, never inserted: a drawer
 * that does not match at a shift close, a ticket the printer gave up on, and an item a sale pushed
 * past its reorder point. Each is written inside the transaction that caused it, so what is under
 * test is both that the card appears and that it appears attached to something that actually
 * happened.
 *
 * <p>The read flags are the rail's own state — a bell with an unread badge, one card dismissed, or
 * the lot cleared at once (design.md S2).
 */
class AlertFeedIT extends AbstractApiIntegrationTest {

    private static final Duration ARRIVES = Duration.ofSeconds(10);
    private static final int PS5_HALF_HOUR = 80;
    private static final int FLOAT = 2000;

    @LocalServerPort
    private int port;

    @Autowired
    private PrintQueueWorker worker;

    @Autowired
    private FakePrinterPortProvider printers;

    private FakePrinterPort printer;
    private FloorFixtures floor;
    private HttpHeaders staff;
    private SseClient events;

    @BeforeEach
    void seedFloor() {
        printer = printers.port();
        printer.reset();
        floor = new FloorFixtures(jdbc);
        staff = adminBearer();
        events = SseClient.connect("http://localhost:" + port + "/api/v1/events",
                staff.getFirst(HttpHeaders.AUTHORIZATION).substring("Bearer ".length()));
    }

    @AfterEach
    void unsubscribe() {
        events.close();
    }

    // ---- what raises a card -------------------------------------------------------------------

    @Test
    @DisplayName("a drawer that does not match writes a discrepancy card and pushes it live")
    void discrepancyRaisesAnAlert() {
        long shiftId = openShift().get("id").asLong();
        Long stationId = createStation("PS5-01", "PS5");
        Long sessionId = floor.runningSessionOn(stationId, shiftId, 1, PS5_HALF_HOUR, 0);
        settle(Map.of("target", Map.of("sessionId", sessionId),
                "splits", List.of(Map.of("method", "CASH", "amount", PS5_HALF_HOUR))));
        events.clear();

        // 2080 expected, 2000 counted: 80 short.
        close(FLOAT);

        JsonNode pushed = events.await("alert", ARRIVES).data();
        assertThat(pushed.get("type").asText()).isEqualTo("CASH_DISCREPANCY");
        assertThat(pushed.get("read").asBoolean()).isFalse();
        assertThat(pushed.get("body").asText()).contains("Shift #" + shiftId);
        // The event is one row of the feed, not a shape of its own (ARCHITECTURE.md §4.5).
        assertThat(pushed).isEqualTo(feed(false).get(0));
    }

    @Test
    @DisplayName("a printer that gives up writes a card naming the ticket it lost")
    void printerFailureRaisesAnAlert() {
        openShift();
        Long stationId = createStation("PS5-01", "PS5");
        Long sessionId = floor.runningSessionOn(stationId,
                jdbc.queryForObject("SELECT id FROM shifts WHERE closed_at IS NULL", Long.class),
                1, PS5_HALF_HOUR, 0);
        settle(Map.of("target", Map.of("sessionId", sessionId),
                "splits", List.of(Map.of("method", "CASH", "amount", PS5_HALF_HOUR))));
        printer.setStatus(PrinterStatus.OFFLINE);
        events.clear();

        worker.drain(TERMINAL);

        JsonNode pushed = events.await("alert", ARRIVES).data();
        assertThat(pushed.get("type").asText()).isEqualTo("PRINTER_FAILED");
        assertThat(pushed.get("title").asText()).contains("Print failed");
        assertThat(feed(true)).hasSize(1);
        // The device's status went out with it, so the counter's printer banner moves too.
        assertThat(events.await("printer-status", ARRIVES).data().get(0).get("status").asText())
                .isEqualTo("OFFLINE");
    }

    @Test
    @DisplayName("a sale that crosses an item's reorder point writes one low-stock card")
    void lowStockRaisesOneAlertOnTheCrossing() {
        openShift();
        Long coke = createItem("Coke", 60, 6, 5);

        settleCartOf(coke, 2);

        List<JsonNode> raised = feed(true);
        assertThat(raised).hasSize(1);
        assertThat(raised.get(0).get("type").asText()).isEqualTo("LOW_STOCK");
        assertThat(raised.get(0).get("title").asText()).isEqualTo("Coke is down to 4");
        assertThat(raised.get(0).get("body").asText()).contains("reorder point of 5");

        // Already below the line: the next sale is not news, and the rail does not fill up with it.
        settleCartOf(coke, 1);

        assertThat(feed(true)).hasSize(1);
    }

    // ---- the rail's own state -----------------------------------------------------------------

    @Test
    @DisplayName("the feed is newest first, filters to unread, and clears a card at a time")
    void readFlags() {
        openShift();
        Long coke = createItem("Coke", 60, 6, 5);
        settleCartOf(coke, 2);
        close(FLOAT + 40);

        List<JsonNode> all = feed(false);
        assertThat(all).hasSize(2);
        assertThat(all.get(0).get("type").asText()).isEqualTo("CASH_DISCREPANCY");
        assertThat(all.get(1).get("type").asText()).isEqualTo("LOW_STOCK");
        assertThat(feed(true)).hasSize(2);

        long stockCard = all.get(1).get("id").asLong();
        ResponseEntity<JsonNode> read = post("/api/v1/alerts/" + stockCard + "/read", null, staff);
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read.getBody().get("read").asBoolean()).isTrue();

        assertThat(feed(true)).hasSize(1);
        assertThat(feed(false)).hasSize(2);

        // Pressing it again says the same thing rather than complaining.
        assertThat(post("/api/v1/alerts/" + stockCard + "/read", null, staff)
                .getBody().get("read").asBoolean()).isTrue();

        ResponseEntity<JsonNode> cleared = post("/api/v1/alerts/read-all", null, staff);
        assertThat(cleared.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cleared.getBody()).hasSize(2);
        assertThat(feed(true)).isEmpty();
    }

    @Test
    @DisplayName("an alert that is not there is 404")
    void unknownAlert() {
        assertErrorEnvelope(post("/api/v1/alerts/999999/read", null, staff), 404, "NOT_FOUND");
    }

    // ---- helpers ------------------------------------------------------------------------------

    private List<JsonNode> feed(boolean unreadOnly) {
        ResponseEntity<JsonNode> response =
                get("/api/v1/alerts" + (unreadOnly ? "?unread=true" : ""), staff);
        assertThat(response.getStatusCode()).as("feed failed: %s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        List<JsonNode> rows = new java.util.ArrayList<>();
        response.getBody().forEach(rows::add);
        return rows;
    }

    private JsonNode openShift() {
        ResponseEntity<JsonNode> opened =
                post("/api/v1/shifts", Map.of("openingFloat", FLOAT), staff);
        assertThat(opened.getStatusCode()).as("open failed: %s", opened.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return opened.getBody();
    }

    private void close(int countedCash) {
        ResponseEntity<JsonNode> closed = post("/api/v1/shifts/current/close",
                Map.of("countedCash", countedCash), staff);
        assertThat(closed.getStatusCode()).as("close failed: %s", closed.getBody())
                .isEqualTo(HttpStatus.OK);
    }

    /** One counter sale of {@code qty}, which is what moves the shelf (invariant §5.3). */
    private void settleCartOf(Long itemId, int qty) {
        Long cartId = post("/api/v1/carts", Map.of("type", "COUNTER"), staff)
                .getBody().get("id").asLong();
        put("/api/v1/carts/" + cartId + "/lines", Map.of("itemId", itemId, "qty", qty), staff);
        settle(Map.of("target", Map.of("cartId", cartId),
                "splits", List.of(Map.of("method", "CASH", "amount", 60 * qty))));
    }

    private JsonNode settle(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(staff);
        headers.add("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<JsonNode> settled = post("/api/v1/payments", body, headers);
        assertThat(settled.getStatusCode()).as("settle failed: %s", settled.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return settled.getBody();
    }

    private Long createItem(String name, int price, int stock, int reorderAt) {
        ResponseEntity<JsonNode> created = post("/api/v1/items",
                Map.of("name", name, "category", "BEVERAGE", "price", price, "stock", stock,
                        "reorderAt", reorderAt), staff);
        assertThat(created.getStatusCode()).as("item failed: %s", created.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return created.getBody().get("id").asLong();
    }

    private Long createStation(String name, String consoleType) {
        return post("/api/v1/stations", Map.of("name", name, "consoleType", consoleType), staff)
                .getBody().get("id").asLong();
    }
}
