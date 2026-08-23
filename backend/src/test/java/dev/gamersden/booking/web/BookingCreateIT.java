package dev.gamersden.booking.web;

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
 * {@code POST /bookings} — pay first, hold the slot (docs/bookings.md §2).
 *
 * <p>What is proved here is that a booking and its money are the same event: one transaction
 * carrying the play time <em>and</em> the package fee in {@code booking_amount}, the booking row
 * that references it, and the print job with the P1 receipt and the P7 confirmation on one piece of
 * paper. A refusal writes none of the three, and a retry under the same {@code Idempotency-Key}
 * charges once and books once (invariants §5.2, §5.3, §5.7).
 */
class BookingCreateIT extends AbstractApiIntegrationTest {

    private static final int PS5_HALF_HOUR = 80;
    private static final int PS5_MORNING_HALF_HOUR = 60;
    private static final int PACKAGE_FEE = 100;

    private BookingFixtures fixtures;
    private FloorFixtures floor;
    private HttpHeaders staff;
    private Long stationId;
    private Long shiftId;

    @BeforeEach
    void seed() {
        fixtures = new BookingFixtures(jdbc);
        floor = new FloorFixtures(jdbc);
        staff = adminBearer();
        stationId = createStation("PS5-01", "PS5");
        shiftId = floor.openShift(adminId, TERMINAL);
    }

    // ---- the whole footprint of one booking ------------------------------------------------------

    @Test
    @DisplayName("play time and the package fee are charged in one transaction, in booking_amount")
    void playAndFeeInOneTransaction() {
        JsonNode created = create(body(3, "CASH"));

        JsonNode booking = created.get("booking");
        long txId = created.get("transactionId").asLong();
        assertThat(booking.get("playAmount").asInt()).isEqualTo(3 * PS5_HALF_HOUR);
        assertThat(booking.get("packageFee").asInt()).isEqualTo(PACKAGE_FEE);
        assertThat(booking.get("total").asInt()).isEqualTo(3 * PS5_HALF_HOUR + PACKAGE_FEE);
        assertThat(booking.get("status").asText()).isEqualTo("PAID");
        assertThat(booking.get("cutoffHours").asInt()).isEqualTo(2);
        // Omitted rather than sent as null (default-property-inclusion: non_null).
        assertThat(booking.get("queueEntryId")).isNull();

        // One transaction, and the whole of it in the pre-booking bucket — that is the X/Z line.
        Map<String, Object> tx = fixtures.transaction(txId);
        assertThat(tx).containsEntry("gaming_amount", 0)
                .containsEntry("fnb_amount", 0)
                .containsEntry("tournament_amount", 0)
                .containsEntry("booking_amount", 3 * PS5_HALF_HOUR + PACKAGE_FEE)
                .containsEntry("total_due", 3 * PS5_HALF_HOUR + PACKAGE_FEE)
                .containsEntry("shift_id", shiftId);
        assertThat(tx.get("session_id")).isNull();
        assertThat(tx.get("cart_id")).isNull();
        assertThat(countOf("transactions")).isEqualTo(1);

        // The booking row references it, and nothing else was written.
        Map<String, Object> row = fixtures.booking(booking.get("id").asLong());
        assertThat(row).containsEntry("tx_id", txId)
                .containsEntry("status", "PAID")
                .containsEntry("blocks", 3)
                .containsEntry("play_amount", 3 * PS5_HALF_HOUR)
                .containsEntry("package_fee", PACKAGE_FEE)
                .containsEntry("cutoff_hours", 2)
                .containsEntry("name", "Rifat Hasan");
        assertThat(row.get("queue_entry_id")).isNull();
        assertThat(countOf("queue_entries")).isZero();

        // The tender went in as one split, and the receipt carries P1 and P7 on the same job.
        assertThat(fixtures.tendersOf(txId)).hasSize(1);
        assertThat(fixtures.tendersOf(txId).get(0)).containsEntry("method", "CASH")
                .containsEntry("amount", 3 * PS5_HALF_HOUR + PACKAGE_FEE);
        long printJobId = created.get("printJobId").asLong();
        assertThat(fixtures.printJobTypeOf(printJobId)).isEqualTo("RECEIPT");
        assertThat(fixtures.paperOf(printJobId))
                .contains("BOOKING 3x30M")
                .contains("PACKAGE FEE")
                .contains("BOOKING CONFIRMED")
                .contains("Rifat Hasan")
                .contains("CANCEL BY")
                .contains("Full refund until");
        // B17: the job carries real P1+P7 ESC/POS, not the placeholder's plain text.
        assertThat(fixtures.renderedOf(printJobId)).startsWith((byte) 0x1B, (byte) 0x40);
        assertThat(countOf("print_jobs")).isEqualTo(1);
    }

    @Test
    @DisplayName("the price is the rate at the booked time, not at the time of booking")
    void pricedForTheSlotNotTheSale() {
        // 05:30 UTC is 11:30 in Dhaka — inside the 10:00-14:00 morning window, so -25%.
        OffsetDateTime morningSlot = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1)
                .withHour(5).withMinute(30).withSecond(0).withNano(0);
        Map<String, Object> request = body(2, "CASH");
        request.put("startAt", morningSlot.toString());

        JsonNode created = create(request);

        assertThat(created.get("booking").get("playAmount").asInt())
                .isEqualTo(2 * PS5_MORNING_HALF_HOUR);
    }

    @Test
    @DisplayName("a member on the booking gets the sale, its points and the wallet as a method")
    void memberBooking() {
        Long memberId = createMember("Nafis Iqbal", "+8801712448190", 1000);
        Map<String, Object> request = body(2, "WALLET");
        request.put("memberId", memberId);

        JsonNode created = create(request);

        long txId = created.get("transactionId").asLong();
        assertThat(fixtures.transaction(txId)).containsEntry("member_id", memberId)
                .containsEntry("points_earned", (2 * PS5_HALF_HOUR + PACKAGE_FEE) / 20);
        assertThat(fixtures.booking(created.get("booking").get("id").asLong()))
                .containsEntry("member_id", memberId);
        assertThat(jdbc.queryForObject("SELECT wallet FROM members WHERE id = ?", Integer.class,
                memberId)).isEqualTo(1000 - (2 * PS5_HALF_HOUR + PACKAGE_FEE));
    }

    @Test
    @DisplayName("a blank name falls back to the attached member's")
    void memberNamesTheBooking() {
        Long memberId = createMember("Tanvir Ahmed", "+8801812448191", 0);
        Map<String, Object> request = body(1, "CASH");
        request.put("memberId", memberId);
        request.remove("name");

        JsonNode created = create(request);

        assertThat(created.get("booking").get("name").asText()).isEqualTo("Tanvir Ahmed");
    }

    // ---- the feature flag ------------------------------------------------------------------------

    @Test
    @DisplayName("pre-booking switched off refuses new bookings with 409 PREBOOKING_DISABLED")
    void disabledBlocksCreate() {
        fixtures.enabled(false);

        ResponseEntity<JsonNode> refused = post("/api/v1/bookings",
                body(2, "CASH"), withKey(UUID.randomUUID().toString()));

        assertErrorEnvelope(refused, 409, "PREBOOKING_DISABLED");
        assertThat(countOf("bookings")).isZero();
        assertThat(countOf("transactions")).isZero();
        assertThat(countOf("print_jobs")).isZero();
    }

    // ---- refusals leave nothing behind ------------------------------------------------------------

    /**
     * The contract's {@code SPLIT_MISMATCH} cannot be reached from this endpoint: the body carries
     * no amount, so the server prices the slot and tenders exactly what it priced. What <em>is</em>
     * reachable is every other way a tender can be refused, and each of them has to leave the
     * booking unwritten — the settle and the booking row are one transaction (invariant §5.3).
     */
    @Test
    @DisplayName("a wallet booking beyond the balance is 409 WALLET_INSUFFICIENT and books nobody")
    void walletMustCoverTheBooking() {
        Long memberId = createMember("Sadia Rahman", "+8801512448193", 50);
        Map<String, Object> request = body(2, "WALLET");
        request.put("memberId", memberId);

        assertErrorEnvelope(post("/api/v1/bookings", request,
                withKey(UUID.randomUUID().toString())), 409, "WALLET_INSUFFICIENT");
        assertThat(countOf("bookings")).isZero();
        assertThat(countOf("transactions")).isZero();
        assertThat(countOf("print_jobs")).isZero();
    }

    @Test
    @DisplayName("a wallet booking with no member attached is refused")
    void walletNeedsAMember() {
        assertErrorEnvelope(post("/api/v1/bookings", body(2, "WALLET"),
                withKey(UUID.randomUUID().toString())), 400, "VALIDATION_FAILED");
        assertThat(countOf("bookings")).isZero();
    }

    @Test
    @DisplayName("a bKash booking with no TrxID is 409 PAYMENT_REF_REQUIRED")
    void bkashNeedsAReference() {
        assertErrorEnvelope(post("/api/v1/bookings", body(2, "BKASH"),
                withKey(UUID.randomUUID().toString())), 409, "PAYMENT_REF_REQUIRED");
        assertThat(countOf("bookings")).isZero();
        assertThat(countOf("transactions")).isZero();
    }

    @Test
    @DisplayName("a slot that has already started is refused")
    void slotMustBeInTheFuture() {
        Map<String, Object> request = body(2, "CASH");
        request.put("startAt", OffsetDateTime.now(ZoneOffset.UTC).minusHours(1).toString());

        assertErrorEnvelope(post("/api/v1/bookings", request, withKey(UUID.randomUUID().toString())),
                400, "VALIDATION_FAILED");
        assertThat(countOf("bookings")).isZero();
    }

    @Test
    @DisplayName("an unknown station is 404 and books nothing")
    void unknownStation() {
        Map<String, Object> request = body(2, "CASH");
        request.put("stationId", stationId + 9999);

        assertErrorEnvelope(post("/api/v1/bookings", request, withKey(UUID.randomUUID().toString())),
                404, "NOT_FOUND");
        assertThat(countOf("bookings")).isZero();
    }

    @Test
    @DisplayName("a booking without an open shift is refused — money lands in a counted drawer")
    void needsAnOpenShift() {
        jdbc.update("UPDATE shifts SET closed_at = now() WHERE id = ?", shiftId);

        assertErrorEnvelope(post("/api/v1/bookings", body(2, "CASH"),
                withKey(UUID.randomUUID().toString())), 409, "CONFLICT");
        assertThat(countOf("bookings")).isZero();
    }

    // ---- the overlap warning ----------------------------------------------------------------------

    @Test
    @DisplayName("two bookings on one console at one time are allowed, and both are flagged")
    void overlapWarnsButDoesNotRefuse() {
        long first = create(body(2, "CASH"))
                .get("booking").get("id").asLong();

        JsonNode second = create(body(2, "CASH"));

        assertThat(second.get("overlappingBookingIds")).hasSize(1);
        assertThat(second.get("overlappingBookingIds").get(0).asLong()).isEqualTo(first);
        assertThat(countOf("bookings")).isEqualTo(2);

        JsonNode upcoming = get("/api/v1/bookings?tab=upcoming", staff).getBody();
        assertThat(upcoming).hasSize(2);
        assertThat(upcoming).allSatisfy(row -> assertThat(row.get("overlapping").asBoolean()).isTrue());
    }

    @Test
    @DisplayName("back-to-back slots on one console do not overlap")
    void adjacentSlotsAreFine() {
        create(body(2, "CASH"));

        Map<String, Object> later = body(2, "CASH");
        later.put("startAt", tomorrowAtSix().plusHours(1).toString());
        JsonNode second = create(later);

        assertThat(second.get("overlappingBookingIds")).isEmpty();
    }

    // ---- the tabs -----------------------------------------------------------------------------------

    @Test
    @DisplayName("upcoming is PAID; a cancelled booking moves to history")
    void tabs() {
        long bookingId = create(body(2, "CASH"))
                .get("booking").get("id").asLong();
        assertThat(get("/api/v1/bookings?tab=upcoming", staff).getBody()).hasSize(1);
        assertThat(get("/api/v1/bookings?tab=history", staff).getBody()).isEmpty();

        post("/api/v1/bookings/" + bookingId + "/cancel", Map.of("reason", "customer called"),
                withKey(UUID.randomUUID().toString()));

        assertThat(get("/api/v1/bookings?tab=upcoming", staff).getBody()).isEmpty();
        JsonNode history = get("/api/v1/bookings?tab=history", staff).getBody();
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("an unknown tab is refused rather than silently listing upcoming")
    void unknownTab() {
        assertErrorEnvelope(get("/api/v1/bookings?tab=everything", staff), 400, "VALIDATION_FAILED");
    }

    @Test
    @DisplayName("the member card lists the bookings the contract promises there")
    void memberDetailListsBookings() {
        Long memberId = createMember("Rifat Hasan", "+8801912448192", 0);
        Map<String, Object> request = body(2, "CASH");
        request.put("memberId", memberId);
        long bookingId = create(request).get("booking").get("id").asLong();

        JsonNode member = get("/api/v1/members/" + memberId, staff).getBody();

        assertThat(member.get("bookings")).hasSize(1);
        JsonNode row = member.get("bookings").get(0);
        assertThat(row.get("bookingId").asLong()).isEqualTo(bookingId);
        assertThat(row.get("status").asText()).isEqualTo("PAID");
        assertThat(row.get("total").asInt()).isEqualTo(2 * PS5_HALF_HOUR + PACKAGE_FEE);
        assertThat(row.get("tokenNo")).isNull();
    }

    // ---- idempotency ---------------------------------------------------------------------------------

    @Test
    @DisplayName("a replayed create books once, charges once and prints once")
    void replayBooksOnce() {
        String key = UUID.randomUUID().toString();
        Map<String, Object> request = body(2, "CASH");

        ResponseEntity<JsonNode> first = post("/api/v1/bookings", request, withKey(key));
        ResponseEntity<JsonNode> replay = post("/api/v1/bookings", request, withKey(key));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(countOf("bookings")).isEqualTo(1);
        assertThat(countOf("transactions")).isEqualTo(1);
        assertThat(countOf("print_jobs")).isEqualTo(1);
    }

    @Test
    @DisplayName("the same key with a different booking is 409 IDEMPOTENCY_REPLAY")
    void mutatedBodyUnderTheSameKey() {
        String key = UUID.randomUUID().toString();
        post("/api/v1/bookings", body(2, "CASH"), withKey(key));

        assertErrorEnvelope(post("/api/v1/bookings", body(4, "CASH"), withKey(key)),
                409, "IDEMPOTENCY_REPLAY");
        assertThat(countOf("bookings")).isEqualTo(1);
    }

    @Test
    @DisplayName("creating without an Idempotency-Key is refused")
    void keyIsRequired() {
        assertErrorEnvelope(post("/api/v1/bookings", body(2, "CASH"), staff),
                400, "VALIDATION_FAILED");
        assertThat(countOf("bookings")).isZero();
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    private JsonNode create(Map<String, Object> request) {
        ResponseEntity<JsonNode> created =
                post("/api/v1/bookings", request, withKey(UUID.randomUUID().toString()));
        assertThat(created.getStatusCode()).as("create failed: %s", created.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return created.getBody();
    }

    /**
     * The booking form's body. Deliberately carries no amount: the server prices the slot from the
     * rate card and the package fee from the settings, and a client that sent a figure would be
     * asserting what the venue charges (invariant §5.11).
     */
    private Map<String, Object> body(int blocks, String method) {
        Map<String, Object> request = new HashMap<>();
        request.put("stationId", stationId);
        request.put("name", "Rifat Hasan");
        request.put("phone", "+8801711223344");
        request.put("startAt", tomorrowAtSix().toString());
        request.put("blocks", blocks);
        request.put("method", method);
        return request;
    }

    /**
     * Tomorrow at 18:00 in Dhaka, expressed in UTC so the test does not depend on the machine's
     * zone. Outside the 10:00-14:00 morning window, so the block price is the plain PS5 rate.
     */
    private static OffsetDateTime tomorrowAtSix() {
        return OffsetDateTime.now(ZoneOffset.UTC).plusDays(1)
                .withHour(12).withMinute(0).withSecond(0).withNano(0);
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
        if (wallet > 0) {
            jdbc.update("UPDATE members SET wallet = ? WHERE id = ?", wallet, id);
            jdbc.update("INSERT INTO wallet_ledger (member_id, delta, kind) VALUES (?, ?, 'TOPUP')",
                    id, wallet);
        }
        return id;
    }

    private int countOf(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
