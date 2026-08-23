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

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code DELETE /play-queue/{id}} — "Manager+ may refund &amp; remove a waiting entry"
 * (docs/bookings.md §3).
 *
 * <p>The refund is the substance of it. Money goes back out as its own negative transaction
 * posted to the shift doing the refunding, never as an edit to the sale (invariant §5.7), and for
 * exactly the amount the token was sold at — the snapshot, not what the rate card says by the time
 * the customer fails to turn up (§5.11). The token is kept as REFUNDED rather than deleted,
 * because the refund hangs off it.
 *
 * <p>Two refusals. A cashier cannot do it — it is money leaving the drawer, so §1's matrix makes
 * it Manager+, and the API enforces that rather than the UI. And a checked-in booking's token is
 * out of scope on purpose: its sale also took a package fee, so docs/bookings.md §7 sends that
 * case to a Manager+ void of the transaction, which reverses the whole sale and revokes the token
 * with it.
 */
class PlayQueueNoShowIT extends AbstractApiIntegrationTest {

    private static final int PS5_HALF_HOUR = 80;
    private static final int PACKAGE_FEE = 100;

    private BookingFixtures fixtures;
    private HttpHeaders staff;
    private Long shiftId;
    private Long ps5;

    @BeforeEach
    void seed() {
        fixtures = new BookingFixtures(jdbc);
        staff = adminBearer();
        ps5 = createStation("PS5-01", "PS5");
        shiftId = new FloorFixtures(jdbc).openShift(adminId, TERMINAL);
    }

    // ---- the refund -------------------------------------------------------------------------------

    @Test
    @DisplayName("removing a no-show writes the refund and takes the token off the rail")
    void removalWritesTheRefund() {
        JsonNode sold = sellTicket("PS5", 3, "Rifat Hasan");
        long queueEntryId = sold.get("token").get("queueEntryId").asLong();
        long saleTxId = sold.get("transactionId").asLong();

        ResponseEntity<JsonNode> removed = delete(
                "/api/v1/play-queue/" + queueEntryId + "?reason=No-show", staff);

        assertThat(removed.getStatusCode()).as("removal failed: %s", removed.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(removed.getBody().get("entry").get("status").asText()).isEqualTo("REFUNDED");
        assertThat(removed.getBody().get("refund").get("amount").asInt())
                .isEqualTo(-3 * PS5_HALF_HOUR);

        // A negative transaction of its own, in the refunding shift, out of the booking bucket.
        long refundTxId = removed.getBody().get("refund").get("transactionId").asLong();
        assertThat(fixtures.transaction(refundTxId))
                .containsEntry("total_due", -3 * PS5_HALF_HOUR)
                .containsEntry("booking_amount", -3 * PS5_HALF_HOUR)
                .containsEntry("shift_id", shiftId);
        assertThat(fixtures.tendersOf(refundTxId)).singleElement()
                .satisfies(tender -> assertThat(tender).containsEntry("method", "CASH")
                        .containsEntry("amount", -3 * PS5_HALF_HOUR));

        // The sale itself is untouched — a refund is never an edit.
        assertThat(fixtures.transaction(saleTxId))
                .containsEntry("total_due", 3 * PS5_HALF_HOUR)
                .containsEntry("booking_amount", 3 * PS5_HALF_HOUR);

        // The row is kept, and simply stops being listed.
        assertThat(fixtures.tokenStatusOf(queueEntryId)).isEqualTo("REFUNDED");
        assertThat(get("/api/v1/play-queue", staff).getBody()).isEmpty();
    }

    @Test
    @DisplayName("the refund is the price the token was sold at, not today's rate card")
    void refundsTheSnapshotNotTheRateCard() {
        JsonNode sold = sellTicket("PS5", 2, "Nafis Iqbal");
        long queueEntryId = sold.get("token").get("queueEntryId").asLong();

        // The venue puts its prices up between the sale and the no-show.
        assertThat(put("/api/v1/pricing/PS5", Map.of("perHalfHour", 200), staff).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        JsonNode removed = delete("/api/v1/play-queue/" + queueEntryId, staff).getBody();

        assertThat(removed.get("refund").get("amount").asInt()).isEqualTo(-2 * PS5_HALF_HOUR);
    }

    @Test
    @DisplayName("a refunded token can no longer be seated")
    void refundedTokenCannotBeSeated() {
        long queueEntryId = sellTicket("PS5", 1, "Rifat Hasan")
                .get("token").get("queueEntryId").asLong();
        delete("/api/v1/play-queue/" + queueEntryId, staff);

        assertErrorEnvelope(post("/api/v1/play-queue/" + queueEntryId + "/seat",
                Map.of("stationId", ps5), staff), 409, "CONFLICT");
        assertErrorEnvelope(post("/api/v1/sessions",
                Map.of("stationId", ps5, "queueEntryId", queueEntryId), staff), 409, "CONFLICT");
        assertThat(countOf("sessions")).isZero();
    }

    // ---- refusals ----------------------------------------------------------------------------------

    @Test
    @DisplayName("a second removal is 409 and writes no second refund")
    void secondRemovalIsRefused() {
        long queueEntryId = sellTicket("PS5", 1, "Rifat Hasan")
                .get("token").get("queueEntryId").asLong();
        delete("/api/v1/play-queue/" + queueEntryId, staff);

        assertErrorEnvelope(delete("/api/v1/play-queue/" + queueEntryId, staff), 409, "CONFLICT");
        assertThat(fixtures.refundsOf(shiftId)).hasSize(1);
    }

    @Test
    @DisplayName("a seated token has been played — there is nothing to refund")
    void seatedTokenCannotBeRemoved() {
        long queueEntryId = sellTicket("PS5", 1, "Rifat Hasan")
                .get("token").get("queueEntryId").asLong();
        post("/api/v1/play-queue/" + queueEntryId + "/seat", Map.of("stationId", ps5), staff);

        assertErrorEnvelope(delete("/api/v1/play-queue/" + queueEntryId, staff), 409, "CONFLICT");
        assertThat(fixtures.refundsOf(shiftId)).isEmpty();
    }

    @Test
    @DisplayName("a checked-in booking's token goes back through a void, not through here")
    void bookingTokenIsRefusedAndPointsAtTheVoid() {
        long bookingId = book("Tanvir Ahmed", 2);
        long queueEntryId = post("/api/v1/bookings/" + bookingId + "/check-in", null, staff)
                .getBody().get("token").get("queueEntryId").asLong();

        assertErrorEnvelope(delete("/api/v1/play-queue/" + queueEntryId, staff), 409, "CONFLICT");
        assertThat(fixtures.tokenStatusOf(queueEntryId)).isEqualTo("WAITING");
        assertThat(fixtures.refundsOf(shiftId)).isEmpty();

        // The documented route does work, and revokes the token with the sale.
        long saleTxId = ((Number) fixtures.booking(bookingId).get("tx_id")).longValue();
        ResponseEntity<JsonNode> voided = post("/api/v1/payments/" + saleTxId + "/void",
                Map.of("reason", "No-show, refunded at the counter"), staff);

        assertThat(voided.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fixtures.tokenStatusOf(queueEntryId)).isEqualTo("REFUNDED");
        assertThat(fixtures.refundsOf(shiftId)).singleElement()
                .satisfies(refund -> assertThat(refund)
                        .containsEntry("total_due", -(2 * PS5_HALF_HOUR + PACKAGE_FEE)));
    }

    @Test
    @DisplayName("a cashier cannot hand money back — the API says so, not the UI")
    void cashierCannotRemove() {
        long queueEntryId = sellTicket("PS5", 1, "Rifat Hasan")
                .get("token").get("queueEntryId").asLong();
        Long cashierId = post("/api/v1/staff",
                Map.of("name", "Rafi", "role", "CASHIER", "pin", "4321"), staff)
                .getBody().get("id").asLong();

        assertErrorEnvelope(delete("/api/v1/play-queue/" + queueEntryId,
                bearerFor(cashierId, "4321")), 403, "FORBIDDEN");

        assertThat(fixtures.tokenStatusOf(queueEntryId)).isEqualTo("WAITING");
        assertThat(fixtures.refundsOf(shiftId)).isEmpty();
    }

    @Test
    @DisplayName("an unknown token is 404")
    void unknownToken() {
        assertErrorEnvelope(delete("/api/v1/play-queue/999999", staff), 404, "NOT_FOUND");
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private JsonNode sellTicket(String consoleType, int blocks, String playerName) {
        ResponseEntity<JsonNode> sold = post("/api/v1/play-tickets",
                Map.of("consoleType", consoleType, "blocks", blocks, "playerName", playerName,
                        "method", "CASH"),
                withKey(UUID.randomUUID().toString()));
        assertThat(sold.getStatusCode()).as("sale failed: %s", sold.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return sold.getBody();
    }

    private long book(String name, int blocks) {
        Map<String, Object> request = new HashMap<>();
        request.put("stationId", ps5);
        request.put("name", name);
        request.put("startAt", OffsetDateTime.now(ZoneOffset.UTC).plusDays(1)
                .withHour(12).withMinute(0).withSecond(0).withNano(0).toString());
        request.put("blocks", blocks);
        request.put("method", "CASH");
        ResponseEntity<JsonNode> created =
                post("/api/v1/bookings", request, withKey(UUID.randomUUID().toString()));
        assertThat(created.getStatusCode()).as("create failed: %s", created.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return created.getBody().get("booking").get("id").asLong();
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
