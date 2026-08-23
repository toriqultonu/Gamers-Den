package dev.gamersden.queue.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamersden.support.AbstractApiIntegrationTest;
import dev.gamersden.support.BookingFixtures;
import dev.gamersden.support.FloorFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Selling prepaid time while there is nowhere to sit (docs/bookings.md §3).
 *
 * <p>That is the whole premise of the play queue: every console is busy, the customer pays anyway,
 * and what they get is a place in line rather than a seat. So the first thing proved here is the
 * negative one — the sale does not consult the floor. The rest is the footprint of one ticket:
 * a {@code queue_entries} row WAITING against the sale that paid for it, the money in
 * {@code booking_amount} where the X/Z pre-booking line will find it (§6), the P6 stub on the
 * sale's own print job (§5.5), and one number off the counter bookings check in against
 * (§5.10).
 */
class PlayTicketSaleIT extends AbstractApiIntegrationTest {

    private static final int PS5_HALF_HOUR = 80;
    private static final int PS4_HALF_HOUR = 50;

    private BookingFixtures fixtures;
    private FloorFixtures floor;
    private HttpHeaders staff;
    private Long shiftId;
    private Long ps5;
    private Long ps4;

    @BeforeEach
    void seed() {
        fixtures = new BookingFixtures(jdbc);
        floor = new FloorFixtures(jdbc);
        staff = adminBearer();
        ps5 = createStation("PS5-01", "PS5");
        ps4 = createStation("PS4-01", "PS4");
        shiftId = floor.openShift(adminId, TERMINAL);
    }

    // ---- the point of the queue -----------------------------------------------------------------

    @Test
    @DisplayName("a ticket sells with every console busy — token, queue row and booking_amount")
    void sellsWhileEveryConsoleIsBusy() {
        fillTheFloor();

        ResponseEntity<JsonNode> sold = post("/api/v1/payments",
                ticketSale(List.of(ticket("PS5", 3, "Rifat Hasan")), 3 * PS5_HALF_HOUR),
                withKey(UUID.randomUUID().toString()));

        assertThat(sold.getStatusCode()).as("sale failed: %s", sold.getBody())
                .isEqualTo(HttpStatus.CREATED);
        JsonNode token = sold.getBody().get("queueTokens").get(0);
        assertThat(sold.getBody().get("queueTokens")).hasSize(1);
        assertThat(token.get("tokenNo").asInt()).isEqualTo(1);

        long queueEntryId = token.get("queueEntryId").asLong();
        long txId = sold.getBody().get("transactionId").asLong();
        assertThat(fixtures.token(queueEntryId))
                .containsEntry("token_no", 1)
                .containsEntry("source", "PLAY_TICKET")
                .containsEntry("booking_id", null)
                .containsEntry("tx_id", txId)
                .containsEntry("player_name", "Rifat Hasan")
                .containsEntry("console_type", "PS5")
                .containsEntry("blocks", 3)
                .containsEntry("play_amount", 3 * PS5_HALF_HOUR)
                .containsEntry("status", "WAITING")
                .containsEntry("session_id", null);

        // Prepaid play money is pre-booking money: one column, so one X/Z line adds both up (§6).
        assertThat(fixtures.transaction(txId))
                .containsEntry("booking_amount", 3 * PS5_HALF_HOUR)
                .containsEntry("gaming_amount", 0)
                .containsEntry("total_due", 3 * PS5_HALF_HOUR)
                .containsEntry("shift_id", shiftId);

        // Both stations are still exactly as busy as they were — a ticket seats nobody.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sessions WHERE state <> 'CLOSED'", Integer.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("the P6 stub rides on the sale's own receipt, one piece of paper")
    void stubRidesOnTheReceipt() {
        ResponseEntity<JsonNode> sold = post("/api/v1/payments",
                ticketSale(List.of(ticket("PS4", 2, "Nafis Iqbal")), 2 * PS4_HALF_HOUR),
                withKey(UUID.randomUUID().toString()));

        long printJobId = sold.getBody().get("printJobId").asLong();
        long queueEntryId = sold.getBody().get("queueTokens").get(0).get("queueEntryId").asLong();

        assertThat(fixtures.printJobTypeOf(printJobId)).isEqualTo("RECEIPT");
        assertThat(fixtures.paperOf(printJobId))
                .contains("PLAY PS4 2x30M")
                .contains("PLAY TICKET")
                .contains("TOKEN #01")
                .contains("Nafis Iqbal")
                .contains("Tokens reset daily")
                .contains("[CODE128] " + queueEntryId);
        // One job, not two: the receipt and the stub are handed over together (§5.5).
        assertThat(countOf("print_jobs")).isEqualTo(1);
    }

    @Test
    @DisplayName("tickets and booking check-ins share one daily counter, in sale order")
    void oneCounterForBoth() {
        long bookingId = book("Tanvir Ahmed", 1);

        sellOne("PS5", 1, "First");
        int checkedIn = post("/api/v1/bookings/" + bookingId + "/check-in", null, staff)
                .getBody().get("token").get("tokenNo").asInt();
        sellOne("PS4", 1, "Third");

        assertThat(checkedIn).isEqualTo(2);
        assertThat(fixtures.tokensToday())
                .extracting(row -> row.get("token_no"), row -> row.get("source"))
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple(1, "PLAY_TICKET"),
                        org.assertj.core.api.Assertions.tuple(2, "BOOKING"),
                        org.assertj.core.api.Assertions.tuple(3, "PLAY_TICKET"));
    }

    @Test
    @DisplayName("several tickets on one sale take consecutive tokens and one receipt")
    void severalTicketsOnOneSale() {
        ResponseEntity<JsonNode> sold = post("/api/v1/payments",
                ticketSale(List.of(ticket("PS5", 2, "Rifat"), ticket("PS4", 1, "Nafis")),
                        2 * PS5_HALF_HOUR + PS4_HALF_HOUR),
                withKey(UUID.randomUUID().toString()));

        assertThat(sold.getStatusCode()).as("sale failed: %s", sold.getBody())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(sold.getBody().get("queueTokens")).hasSize(2);
        assertThat(sold.getBody().get("queueTokens")).extracting(node -> node.get("tokenNo").asInt())
                .containsExactly(1, 2);
        assertThat(fixtures.transaction(sold.getBody().get("transactionId").asLong()))
                .containsEntry("booking_amount", 2 * PS5_HALF_HOUR + PS4_HALF_HOUR);
        assertThat(countOf("print_jobs")).isEqualTo(1);
    }

    // ---- the standalone alias -----------------------------------------------------------------

    @Test
    @DisplayName("POST /play-tickets is the same settle, priced server-side")
    void standaloneAlias() {
        ResponseEntity<JsonNode> sold = post("/api/v1/play-tickets",
                Map.of("consoleType", "PS5", "blocks", 2, "playerName", "Rifat Hasan",
                        "method", "CASH"),
                withKey(UUID.randomUUID().toString()));

        assertThat(sold.getStatusCode()).as("sale failed: %s", sold.getBody())
                .isEqualTo(HttpStatus.CREATED);
        JsonNode body = sold.getBody();
        assertThat(body.get("amount").asInt()).isEqualTo(2 * PS5_HALF_HOUR);
        assertThat(body.get("token").get("tokenNo").asInt()).isEqualTo(1);
        assertThat(body.get("token").get("consoleType").asText()).isEqualTo("PS5");

        assertThat(fixtures.transaction(body.get("transactionId").asLong()))
                .containsEntry("booking_amount", 2 * PS5_HALF_HOUR)
                .containsEntry("total_due", 2 * PS5_HALF_HOUR);
        assertThat(fixtures.tendersOf(body.get("transactionId").asLong()))
                .singleElement()
                .satisfies(tender -> assertThat(tender).containsEntry("method", "CASH")
                        .containsEntry("amount", 2 * PS5_HALF_HOUR));
    }

    @Test
    @DisplayName("a replayed ticket sale returns the same token and burns no second number")
    void replayIssuesNoSecondToken() {
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("consoleType", "PS5", "blocks", 1, "method", "CASH");

        JsonNode first = post("/api/v1/play-tickets", body, withKey(key)).getBody();
        ResponseEntity<JsonNode> replay = post("/api/v1/play-tickets", body, withKey(key));

        assertThat(replay.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
        assertThat(replay.getBody().get("token").get("queueEntryId").asLong())
                .isEqualTo(first.get("token").get("queueEntryId").asLong());
        assertThat(replay.getBody().get("printJobId").asLong())
                .isEqualTo(first.get("printJobId").asLong());
        assertThat(countOf("queue_entries")).isEqualTo(1);
        assertThat(countOf("transactions")).isEqualTo(1);
        assertThat(fixtures.nextTokenNoOn(today())).isEqualTo(2);
    }

    @Test
    @DisplayName("blank playerName falls back to the walk-in the token is handed to")
    void blankNameFallsBack() {
        JsonNode sold = post("/api/v1/play-tickets",
                Map.of("consoleType", "PS4", "blocks", 1, "method", "CASH"),
                withKey(UUID.randomUUID().toString())).getBody();

        assertThat(sold.get("token").get("playerName").asText()).isEqualTo("Walk-in guest");
    }

    // ---- refusals ------------------------------------------------------------------------------

    @Test
    @DisplayName("a console type the rate card has never heard of is 400, and nothing is written")
    void unknownConsoleTypeIsRefused() {
        assertErrorEnvelope(post("/api/v1/play-tickets",
                Map.of("consoleType", "PS3", "blocks", 1, "method", "CASH"),
                withKey(UUID.randomUUID().toString())), 400, "VALIDATION_FAILED");

        assertThat(countOf("queue_entries")).isZero();
        assertThat(countOf("transactions")).isZero();
        assertThat(fixtures.nextTokenNoOn(today())).isNull();
    }

    @Test
    @DisplayName("a tender that does not cover the tickets is 409 and leaves no token behind")
    void shortTenderLeavesNothing() {
        assertErrorEnvelope(post("/api/v1/payments",
                ticketSale(List.of(ticket("PS5", 2, "Rifat")), PS5_HALF_HOUR),
                withKey(UUID.randomUUID().toString())), 409, "SPLIT_MISMATCH");

        assertThat(countOf("queue_entries")).isZero();
        assertThat(countOf("transactions")).isZero();
        assertThat(countOf("print_jobs")).isZero();
        assertThat(fixtures.nextTokenNoOn(today())).isNull();
    }

    @Test
    @DisplayName("voiding the sale revokes the token it paid for")
    void voidRevokesTheToken() {
        JsonNode sold = post("/api/v1/play-tickets",
                Map.of("consoleType", "PS5", "blocks", 1, "method", "CASH"),
                withKey(UUID.randomUUID().toString())).getBody();
        long queueEntryId = sold.get("token").get("queueEntryId").asLong();

        ResponseEntity<JsonNode> voided = post(
                "/api/v1/payments/" + sold.get("transactionId").asLong() + "/void",
                Map.of("reason", "Rang up on the wrong console"), staff);

        assertThat(voided.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fixtures.tokenStatusOf(queueEntryId)).isEqualTo("REFUNDED");
        assertThat(get("/api/v1/play-queue", staff).getBody()).isEmpty();
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** A live session on every console — the state the play queue exists for. */
    private void fillTheFloor() {
        floor.runningSessionOn(ps5, shiftId, 1, PS5_HALF_HOUR, 0);
        floor.runningSessionOn(ps4, shiftId, 1, PS4_HALF_HOUR, 0);
    }

    private void sellOne(String consoleType, int blocks, String name) {
        ResponseEntity<JsonNode> sold = post("/api/v1/play-tickets",
                Map.of("consoleType", consoleType, "blocks", blocks, "playerName", name,
                        "method", "CASH"),
                withKey(UUID.randomUUID().toString()));
        assertThat(sold.getStatusCode()).as("sale failed: %s", sold.getBody())
                .isEqualTo(HttpStatus.CREATED);
    }

    private long book(String name, int blocks) {
        Map<String, Object> request = new HashMap<>();
        request.put("stationId", ps5);
        request.put("name", name);
        request.put("startAt", java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusDays(1)
                .withHour(12).withMinute(0).withSecond(0).withNano(0).toString());
        request.put("blocks", blocks);
        request.put("method", "CASH");
        ResponseEntity<JsonNode> created =
                post("/api/v1/bookings", request, withKey(UUID.randomUUID().toString()));
        assertThat(created.getStatusCode()).as("create failed: %s", created.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return created.getBody().get("booking").get("id").asLong();
    }

    private static Map<String, Object> ticket(String consoleType, int blocks, String playerName) {
        return Map.of("consoleType", consoleType, "blocks", blocks, "playerName", playerName);
    }

    /** A settle with nothing but {@code playTickets[]} — no session, no cart, nowhere to sit. */
    private static Map<String, Object> ticketSale(List<Map<String, Object>> tickets, int tendered) {
        return Map.of("target", Map.of(),
                "playTickets", tickets,
                "splits", List.of(Map.of("method", "CASH", "amount", tendered)));
    }

    private static java.time.LocalDate today() {
        return java.time.LocalDate.now(dev.gamersden.common.config.VenueTime.ZONE);
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

    private int countOf(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
