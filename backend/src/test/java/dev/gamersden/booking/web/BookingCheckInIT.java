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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /bookings/{id}/check-in} — "Check in and print token" (docs/bookings.md §2).
 *
 * <p>Three things are proved here. The token comes off the one daily counter and its whole
 * footprint — the {@code queue_entries} row, the booking flipped to ARRIVED, the P6 stub — is a
 * single transaction (invariants §5.3, §5.10). The counter survives contention: eight terminals
 * checking in at the same instant take eight consecutive numbers, not eight copies of #1. And the
 * feature flag guards the door, not the building: with pre-booking switched off a booking already
 * paid for still checks in (docs/bookings.md §7).
 */
class BookingCheckInIT extends AbstractApiIntegrationTest {

    private static final int PS5_HALF_HOUR = 80;
    private static final int PACKAGE_FEE = 100;

    /** Enough terminals to make the {@code token_seq} row lock matter. */
    private static final int CONCURRENT_CHECK_INS = 8;

    private BookingFixtures fixtures;
    private HttpHeaders staff;
    private Long stationId;

    @BeforeEach
    void seed() {
        fixtures = new BookingFixtures(jdbc);
        staff = adminBearer();
        stationId = createStation("PS5-01", "PS5");
        new FloorFixtures(jdbc).openShift(adminId, TERMINAL);
    }

    // ---- the whole footprint of one check-in ------------------------------------------------------

    @Test
    @DisplayName("check-in issues the day's next token, writes its queue entry and prints P6")
    void checkInIssuesAToken() {
        long bookingId = book("Rifat Hasan", 3);

        ResponseEntity<JsonNode> response = post("/api/v1/bookings/" + bookingId + "/check-in",
                null, staff);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body.get("token").get("tokenNo").asInt()).isEqualTo(1);
        assertThat(body.get("booking").get("status").asText()).isEqualTo("ARRIVED");
        assertThat(body.get("booking").get("tokenNo").asInt()).isEqualTo(1);

        long queueEntryId = body.get("token").get("queueEntryId").asLong();
        Map<String, Object> booking = fixtures.booking(bookingId);
        assertThat(booking).containsEntry("status", "ARRIVED")
                .containsEntry("queue_entry_id", queueEntryId);

        // The queue entry carries the sale that paid for the time — no token without its money.
        Map<String, Object> token = fixtures.token(queueEntryId);
        assertThat(token).containsEntry("token_no", 1)
                .containsEntry("source", "BOOKING")
                .containsEntry("booking_id", bookingId)
                .containsEntry("tx_id", booking.get("tx_id"))
                .containsEntry("player_name", "Rifat Hasan")
                .containsEntry("console_type", "PS5")
                .containsEntry("blocks", 3)
                .containsEntry("status", "WAITING");

        // P6 stands alone: a check-in takes no money, so there is no receipt for it to ride on.
        long printJobId = body.get("printJobId").asLong();
        assertThat(fixtures.printJobTypeOf(printJobId)).isEqualTo("PLAY_TICKET");
        assertThat(fixtures.paperOf(printJobId))
                .contains("PLAY TICKET - PREBOOKED")
                .contains("TOKEN #01")
                .contains("Rifat Hasan")
                .contains("PS5")
                .contains("[CODE128] " + queueEntryId);
        // One for the booking's receipt, one for this stub.
        assertThat(countOf("print_jobs")).isEqualTo(2);
    }

    @Test
    @DisplayName("the checked-in booking moves from Upcoming to History carrying its token")
    void arrivedShowsInHistory() {
        long bookingId = book("Nafis Iqbal", 2);
        post("/api/v1/bookings/" + bookingId + "/check-in", null, staff);

        assertThat(get("/api/v1/bookings?tab=upcoming", staff).getBody()).isEmpty();
        JsonNode history = get("/api/v1/bookings?tab=history", staff).getBody();
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("status").asText()).isEqualTo("ARRIVED");
        assertThat(history.get(0).get("tokenNo").asInt()).isEqualTo(1);
        assertThat(history.get(0).get("cancellable").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("bookings and their tokens share one counter, in check-in order")
    void tokensRunOnInOrder() {
        long first = book("Rifat", 1);
        long second = book("Nafis", 1);

        assertThat(tokenOf(second)).isEqualTo(1);
        assertThat(tokenOf(first)).isEqualTo(2);
        assertThat(fixtures.tokensToday()).extracting(row -> row.get("player_name"))
                .containsExactly("Nafis", "Rifat");
    }

    // ---- the counter under contention -------------------------------------------------------------

    @Test
    @DisplayName("eight terminals checking in at once take eight consecutive tokens")
    void concurrentCheckInsTakeConsecutiveTokens() throws Exception {
        List<Long> bookingIds = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_CHECK_INS; i++) {
            bookingIds.add(book("Player " + i, 1));
        }

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_CHECK_INS);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ResponseEntity<JsonNode>>> checkIns = new ArrayList<>();
        try {
            for (Long bookingId : bookingIds) {
                checkIns.add(pool.submit(() -> {
                    go.await();
                    return post("/api/v1/bookings/" + bookingId + "/check-in", null, staff);
                }));
            }
            go.countDown();
            List<Integer> issued = new ArrayList<>();
            for (Future<ResponseEntity<JsonNode>> checkIn : checkIns) {
                ResponseEntity<JsonNode> response = checkIn.get(30, TimeUnit.SECONDS);
                assertThat(response.getStatusCode()).as("check-in failed: %s", response.getBody())
                        .isEqualTo(HttpStatus.OK);
                issued.add(response.getBody().get("token").get("tokenNo").asInt());
            }
            assertThat(issued).doesNotHaveDuplicates()
                    .containsExactlyInAnyOrderElementsOf(numbersUpTo(CONCURRENT_CHECK_INS));
        } finally {
            pool.shutdownNow();
        }

        // The counter is left exactly one ahead, and every number has its row.
        assertThat(fixtures.tokensToday()).hasSize(CONCURRENT_CHECK_INS);
        assertThat(fixtures.tokensToday()).extracting(row -> row.get("token_no"))
                .containsExactlyElementsOf(numbersUpTo(CONCURRENT_CHECK_INS));
        assertThat(jdbc.queryForObject("SELECT next_no FROM token_seq", Integer.class))
                .isEqualTo(CONCURRENT_CHECK_INS + 1);
    }

    // ---- the feature flag ---------------------------------------------------------------------------

    @Test
    @DisplayName("pre-booking switched off blocks new bookings but the paid one still checks in")
    void disabledStillChecksIn() {
        long bookingId = book("Tanvir Ahmed", 2);
        fixtures.enabled(false);

        assertErrorEnvelope(post("/api/v1/bookings", body(2, "CASH"),
                withKey(UUID.randomUUID().toString())), 409, "PREBOOKING_DISABLED");

        ResponseEntity<JsonNode> checkedIn =
                post("/api/v1/bookings/" + bookingId + "/check-in", null, staff);

        assertThat(checkedIn.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(checkedIn.getBody().get("token").get("tokenNo").asInt()).isEqualTo(1);
        assertThat(fixtures.statusOf(bookingId)).isEqualTo("ARRIVED");
    }

    // ---- refusals ------------------------------------------------------------------------------------

    @Test
    @DisplayName("a second check-in is 409 ALREADY_CHECKED_IN and issues no second token")
    void secondCheckInIsRefused() {
        long bookingId = book("Rifat Hasan", 1);
        post("/api/v1/bookings/" + bookingId + "/check-in", null, staff);

        assertErrorEnvelope(post("/api/v1/bookings/" + bookingId + "/check-in", null, staff),
                409, "ALREADY_CHECKED_IN");
        assertThat(countOf("queue_entries")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT next_no FROM token_seq", Integer.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("a cancelled booking has nobody to check in")
    void cancelledCannotCheckIn() {
        long bookingId = book("Nafis Iqbal", 1);
        post("/api/v1/bookings/" + bookingId + "/cancel", Map.of(),
                withKey(UUID.randomUUID().toString()));

        assertErrorEnvelope(post("/api/v1/bookings/" + bookingId + "/check-in", null, staff),
                409, "CONFLICT");
        assertThat(countOf("queue_entries")).isZero();
    }

    @Test
    @DisplayName("an unknown booking is 404")
    void unknownBooking() {
        assertErrorEnvelope(post("/api/v1/bookings/999999/check-in", null, staff), 404, "NOT_FOUND");
    }

    @Test
    @DisplayName("a cashier checks customers in — that is the door, not the office")
    void cashierMayCheckIn() {
        long bookingId = book("Rifat Hasan", 1);
        Long cashierId = post("/api/v1/staff",
                Map.of("name", "Rafi", "role", "CASHIER", "pin", "4321"), staff)
                .getBody().get("id").asLong();

        ResponseEntity<JsonNode> checkedIn = post("/api/v1/bookings/" + bookingId + "/check-in",
                null, bearerFor(cashierId, "4321"));

        assertThat(checkedIn.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    private long book(String name, int blocks) {
        Map<String, Object> request = body(blocks, "CASH");
        request.put("name", name);
        ResponseEntity<JsonNode> created =
                post("/api/v1/bookings", request, withKey(UUID.randomUUID().toString()));
        assertThat(created.getStatusCode()).as("create failed: %s", created.getBody())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("booking").get("total").asInt())
                .isEqualTo(blocks * PS5_HALF_HOUR + PACKAGE_FEE);
        return created.getBody().get("booking").get("id").asLong();
    }

    private int tokenOf(long bookingId) {
        ResponseEntity<JsonNode> response =
                post("/api/v1/bookings/" + bookingId + "/check-in", null, staff);
        assertThat(response.getStatusCode()).as("check-in failed: %s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return response.getBody().get("token").get("tokenNo").asInt();
    }

    private Map<String, Object> body(int blocks, String method) {
        Map<String, Object> request = new HashMap<>();
        request.put("stationId", stationId);
        request.put("name", "Rifat Hasan");
        request.put("startAt", tomorrowAtSix().toString());
        request.put("blocks", blocks);
        request.put("method", method);
        return request;
    }

    /** Tomorrow at 18:00 Dhaka, in UTC — outside the morning window, so the rate is fixed. */
    private static OffsetDateTime tomorrowAtSix() {
        return OffsetDateTime.now(ZoneOffset.UTC).plusDays(1)
                .withHour(12).withMinute(0).withSecond(0).withNano(0);
    }

    private static List<Integer> numbersUpTo(int last) {
        List<Integer> numbers = new ArrayList<>(last);
        for (int i = 1; i <= last; i++) {
            numbers.add(i);
        }
        return numbers;
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
