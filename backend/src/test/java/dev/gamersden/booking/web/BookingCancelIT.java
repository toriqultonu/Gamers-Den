package dev.gamersden.booking.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamersden.common.config.VenueTime;
import dev.gamersden.support.AbstractApiIntegrationTest;
import dev.gamersden.support.BookingFixtures;
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
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /bookings/{id}/cancel} (docs/bookings.md §2).
 *
 * <p>The cutoff is the whole test. It is measured against the booking's <em>own</em>
 * {@code cutoff_hours} snapshot and the server clock, never the current setting and never the
 * client's clock (invariants §5.1, §5.11), and the boundary itself is inside the window: at
 * exactly {@code start_at − cutoff_hours} the cancel still goes through, one second later it does
 * not. The application clock is frozen so both sides of that line can be asserted rather than
 * approached.
 *
 * <p>The refund is a transaction, not an edit: the sale row stands exactly as it was printed and a
 * negative row carries the money back out, posted to the shift doing the cancelling
 * (invariant §5.7).
 */
@Import(MutableClockConfig.class)
class BookingCancelIT extends AbstractApiIntegrationTest {

    private static final int PS5_HALF_HOUR = 80;
    private static final int PACKAGE_FEE = 100;
    private static final int CUTOFF_HOURS = 2;

    @Autowired
    private MutableClock clock;

    private BookingFixtures fixtures;
    private HttpHeaders staff;
    private Long stationId;
    private Long shiftId;

    @BeforeEach
    void seed() {
        // The clock moves first, because the access token is minted from it: signing in and then
        // jumping the clock forward would hand the suite a token that is already expired.
        // Tomorrow at 18:00 in Dhaka is outside the 10:00-14:00 morning window, so every booking
        // below is sold at the plain PS5 rate and the refund arithmetic is fixed.
        clock.setToVenueTime(LocalDate.now(VenueTime.ZONE).plusDays(1), LocalTime.of(18, 0));
        fixtures = new BookingFixtures(jdbc);
        staff = adminBearer();
        stationId = createStation("PS5-01", "PS5");
        shiftId = new FloorFixtures(jdbc).openShift(adminId, TERMINAL);
    }

    // ---- the cutoff boundary ------------------------------------------------------------------------

    @Test
    @DisplayName("at exactly the cutoff the cancel still goes through")
    void cancellableAtTheBoundary() {
        long bookingId = book(2, now().plusHours(CUTOFF_HOURS));

        ResponseEntity<JsonNode> cancelled = cancel(bookingId, "customer called");

        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody().get("booking").get("status").asText()).isEqualTo("CANCELLED");
        assertThat(fixtures.statusOf(bookingId)).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("one second inside the window is 409 CANCEL_CUTOFF_PASSED and refunds nothing")
    void refusedOneSecondInside() {
        long bookingId = book(2, now().plusHours(CUTOFF_HOURS).minusSeconds(1));
        int transactionsBefore = countOf("transactions");

        ResponseEntity<JsonNode> refused = cancel(bookingId, "too late");

        assertErrorEnvelope(refused, 409, "CANCEL_CUTOFF_PASSED");
        assertThat(refused.getBody().get("error").get("details").get("cutoffHours").asInt())
                .isEqualTo(CUTOFF_HOURS);
        assertThat(fixtures.statusOf(bookingId)).isEqualTo("PAID");
        assertThat(countOf("transactions")).isEqualTo(transactionsBefore);
    }

    @Test
    @DisplayName("the booking's own cutoff snapshot decides, not the current setting")
    void cutoffIsTheSnapshot() {
        // Sold under a 2-hour window, then the venue tightens it to 12 hours.
        long bookingId = book(2, now().plusHours(3));
        put("/api/v1/booking-settings", Map.of("cancelCutoffHours", 12), staff);

        ResponseEntity<JsonNode> cancelled = cancel(bookingId, "sold under the old terms");

        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fixtures.statusOf(bookingId)).isEqualTo("CANCELLED");
    }

    /**
     * Six minutes, not an hour: the access token is minted from the same clock and lives fifteen
     * minutes, so a bigger jump would prove the token expiry rather than the cutoff.
     */
    @Test
    @DisplayName("time passing closes the window on a booking that was cancellable a moment ago")
    void theWindowCloses() {
        long bookingId = book(2, now().plusHours(CUTOFF_HOURS).plusMinutes(5));
        assertThat(cancellable(bookingId)).isTrue();

        clock.advance(Duration.ofMinutes(6));

        assertErrorEnvelope(cancel(bookingId, "left it too long"), 409, "CANCEL_CUTOFF_PASSED");
        assertThat(fixtures.statusOf(bookingId)).isEqualTo("PAID");
    }

    // ---- the refund ---------------------------------------------------------------------------------

    @Test
    @DisplayName("a cancel hands back the whole booking as one negative transaction")
    void refundIsFullAndNegative() {
        long bookingId = book(3, now().plusHours(6));
        int total = 3 * PS5_HALF_HOUR + PACKAGE_FEE;

        JsonNode cancelled = cancel(bookingId, "customer called").getBody();

        assertThat(cancelled.get("refundAmount").asInt()).isEqualTo(-total);
        long refundTxId = cancelled.get("refundTransactionId").asLong();

        // The refund is its own row, in the pre-booking bucket, in the shift doing the cancelling.
        List<Map<String, Object>> refunds = fixtures.refundsOf(shiftId);
        assertThat(refunds).hasSize(1);
        assertThat(refunds.get(0)).containsEntry("id", refundTxId)
                .containsEntry("total_due", -total)
                .containsEntry("booking_amount", -total);

        // Money goes back the way it came.
        assertThat(fixtures.tendersOf(refundTxId)).hasSize(1);
        assertThat(fixtures.tendersOf(refundTxId).get(0)).containsEntry("method", "CASH")
                .containsEntry("amount", -total);

        // The sale row is untouched, and the booking points at what paid it back.
        Map<String, Object> booking = fixtures.booking(bookingId);
        assertThat(booking).containsEntry("status", "CANCELLED")
                .containsEntry("refund_tx_id", refundTxId);
        assertThat(fixtures.transaction((Long) booking.get("tx_id")))
                .containsEntry("booking_amount", total)
                .containsEntry("total_due", total);
    }

    @Test
    @DisplayName("a wallet-paid booking is refunded back into the wallet")
    void walletRefund() {
        Long memberId = createMember("Nafis Iqbal", "+8801712448190", 1000);
        int total = 2 * PS5_HALF_HOUR + PACKAGE_FEE;
        Map<String, Object> request = body(2, "WALLET", now().plusHours(6));
        request.put("memberId", memberId);
        long bookingId = create(request);
        assertThat(walletOf(memberId)).isEqualTo(1000 - total);

        cancel(bookingId, "customer called");

        assertThat(walletOf(memberId)).isEqualTo(1000);
    }

    // ---- refusals ------------------------------------------------------------------------------------

    @Test
    @DisplayName("a checked-in booking is 409 ALREADY_CHECKED_IN — that money goes back via a void")
    void checkedInCannotCancel() {
        long bookingId = book(2, now().plusHours(6));
        post("/api/v1/bookings/" + bookingId + "/check-in", null, staff);

        assertErrorEnvelope(cancel(bookingId, "changed their mind"), 409, "ALREADY_CHECKED_IN");
        assertThat(fixtures.statusOf(bookingId)).isEqualTo("ARRIVED");
        assertThat(fixtures.refundsOf(shiftId)).isEmpty();
    }

    @Test
    @DisplayName("cancelling twice under different keys is refused the second time")
    void secondCancelIsRefused() {
        long bookingId = book(2, now().plusHours(6));
        cancel(bookingId, "customer called");

        assertErrorEnvelope(cancel(bookingId, "again"), 409, "CONFLICT");
        assertThat(fixtures.refundsOf(shiftId)).hasSize(1);
    }

    @Test
    @DisplayName("an unknown booking is 404 and refunds nothing")
    void unknownBooking() {
        assertErrorEnvelope(cancel(999999L, "nope"), 404, "NOT_FOUND");
        assertThat(fixtures.refundsOf(shiftId)).isEmpty();
    }

    // ---- idempotency ------------------------------------------------------------------------------------

    @Test
    @DisplayName("a replayed cancel refunds once and returns the same refund")
    void replayRefundsOnce() {
        long bookingId = book(2, now().plusHours(6));
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("reason", "customer called");

        ResponseEntity<JsonNode> first =
                post("/api/v1/bookings/" + bookingId + "/cancel", body, withKey(key));
        ResponseEntity<JsonNode> replay =
                post("/api/v1/bookings/" + bookingId + "/cancel", body, withKey(key));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(fixtures.refundsOf(shiftId)).hasSize(1);
        // The sale and its one refund, and nothing else.
        assertThat(countOf("transactions")).isEqualTo(2);
    }

    @Test
    @DisplayName("cancelling without an Idempotency-Key is refused")
    void keyIsRequired() {
        long bookingId = book(2, now().plusHours(6));

        assertErrorEnvelope(post("/api/v1/bookings/" + bookingId + "/cancel", Map.of(), staff),
                400, "VALIDATION_FAILED");
        assertThat(fixtures.statusOf(bookingId)).isEqualTo("PAID");
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), VenueTime.ZONE);
    }

    /** What the row itself says about whether the button should be live. */
    private boolean cancellable(long bookingId) {
        return get("/api/v1/bookings/" + bookingId, staff).getBody().get("cancellable").asBoolean();
    }

    private long book(int blocks, OffsetDateTime startAt) {
        return create(body(blocks, "CASH", startAt));
    }

    private long create(Map<String, Object> request) {
        ResponseEntity<JsonNode> created =
                post("/api/v1/bookings", request, withKey(UUID.randomUUID().toString()));
        assertThat(created.getStatusCode()).as("create failed: %s", created.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return created.getBody().get("booking").get("id").asLong();
    }

    private ResponseEntity<JsonNode> cancel(long bookingId, String reason) {
        return post("/api/v1/bookings/" + bookingId + "/cancel", Map.of("reason", reason),
                withKey(UUID.randomUUID().toString()));
    }

    private Map<String, Object> body(int blocks, String method, OffsetDateTime startAt) {
        Map<String, Object> request = new HashMap<>();
        request.put("stationId", stationId);
        request.put("name", "Rifat Hasan");
        request.put("startAt", startAt.toString());
        request.put("blocks", blocks);
        request.put("method", method);
        return request;
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

    private Long createMember(String name, String phone, int wallet) {
        Long id = jdbc.queryForObject("INSERT INTO members (name, phone) VALUES (?, ?) RETURNING id",
                Long.class, name, phone);
        jdbc.update("UPDATE members SET wallet = ? WHERE id = ?", wallet, id);
        jdbc.update("INSERT INTO wallet_ledger (member_id, delta, kind) VALUES (?, ?, 'TOPUP')",
                id, wallet);
        return id;
    }

    private int walletOf(Long memberId) {
        return jdbc.queryForObject("SELECT wallet FROM members WHERE id = ?", Integer.class,
                memberId);
    }

    private int countOf(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
