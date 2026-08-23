package dev.gamersden.queue.web;

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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Seating a token: the one transaction of invariant §5.9.
 *
 * <p>What has to be true afterwards is a chain, and each link is asserted against the rows
 * Postgres holds rather than the response: the session exists, its blocks are born carrying the
 * <em>original sale's</em> {@code paid_tx_id}, the token is SEATED and points at the session, a
 * booking behind it has flipped to USED — and, the point of all of it, the seat can then be
 * played and ended without anybody paying a second time.
 *
 * <p>Two refusals matter as much as the happy path. A PS5 ticket carried to a PS4 is 409
 * {@code CONSOLE_TYPE_MISMATCH}: prepaid time is prepaid for a machine, not for a queue position.
 * And a token is spent once — seating it twice would load one payment onto two consoles.
 *
 * <p>The last test is the rollover of docs/bookings.md §7. The counter restarts at Asia/Dhaka
 * midnight, but a customer who paid yesterday and never got a console is still owed their time, so
 * yesterday's token keeps its place and still seats. The clock is moved rather than the rows
 * edited, so it is the real day boundary being tested.
 */
@Import(MutableClockConfig.class)
class PlayQueueSeatIT extends AbstractApiIntegrationTest {

    private static final int PS5_HALF_HOUR = 80;
    private static final int PS4_HALF_HOUR = 50;
    private static final int PACKAGE_FEE = 100;

    /** 18:00 Dhaka — clear of the morning window, so the rate card is the plain one. */
    private static final java.time.LocalTime EVENING = java.time.LocalTime.of(18, 0);

    @Autowired
    private MutableClock clock;

    private BookingFixtures fixtures;
    private FloorFixtures floor;
    private HttpHeaders staff;
    private Long shiftId;
    private Long ps5;
    private Long ps4;

    @BeforeEach
    void seed() {
        clock.resetToNow();
        jdbc.update("DELETE FROM idempotency_keys");
        fixtures = new BookingFixtures(jdbc);
        floor = new FloorFixtures(jdbc);
        staff = adminBearer();
        ps5 = createStation("PS5-01", "PS5");
        ps4 = createStation("PS4-01", "PS4");
        shiftId = floor.openShift(adminId, TERMINAL);
    }

    // ---- the seat --------------------------------------------------------------------------------

    @Test
    @DisplayName("seating a ticket opens a session whose blocks are already paid for")
    void seatLoadsPrepaidBlocks() {
        JsonNode sold = sellTicket("PS5", 3, "Rifat Hasan");
        long queueEntryId = sold.get("token").get("queueEntryId").asLong();
        long saleTxId = sold.get("transactionId").asLong();

        ResponseEntity<JsonNode> seated = post(
                "/api/v1/play-queue/" + queueEntryId + "/seat", Map.of("stationId", ps5), staff);

        assertThat(seated.getStatusCode()).as("seat failed: %s", seated.getBody())
                .isEqualTo(HttpStatus.OK);
        JsonNode session = seated.getBody().get("session");
        long sessionId = session.get("id").asLong();
        assertThat(session.get("stationId").asLong()).isEqualTo(ps5);
        assertThat(session.get("state").asText()).isEqualTo("OPEN");
        assertThat(session.get("blocks").asInt()).isEqualTo(3);
        assertThat(session.get("paidBlocks").asInt()).isEqualTo(3);
        assertThat(session.get("netOutstanding").asInt()).isZero();
        assertThat(seated.getBody().get("entry").get("status").asText()).isEqualTo("SEATED");

        // Born paid, carrying the sale that took the money at the counter (invariant §5.9).
        assertThat(fixtures.blocksOf(sessionId)).hasSize(3)
                .allSatisfy(block -> assertThat(block)
                        .containsEntry("price", PS5_HALF_HOUR)
                        .containsEntry("paid_tx_id", saleTxId));

        assertThat(fixtures.token(queueEntryId))
                .containsEntry("status", "SEATED")
                .containsEntry("session_id", sessionId);
        assertThat(jdbc.queryForObject("SELECT queue_entry_id FROM sessions WHERE id = ?",
                Long.class, sessionId)).isEqualTo(queueEntryId);
    }

    @Test
    @DisplayName("the seated session plays out and ends without a second payment")
    void seatedSessionEndsWithNoBalance() {
        JsonNode sold = sellTicket("PS5", 1, "Rifat Hasan");
        long sessionId = seat(sold.get("token").get("queueEntryId").asLong(), ps5)
                .get("session").get("id").asLong();
        int transactionsAfterSale = countOf("transactions");

        assertThat(post("/api/v1/sessions/" + sessionId + "/clock", Map.of("action", "START"),
                staff).getStatusCode()).isEqualTo(HttpStatus.OK);
        // Under the 15-minute access-token life, so the operator is still signed in when they end
        // the seat; the point is that time was played, not how much.
        clock.advance(Duration.ofMinutes(10));

        ResponseEntity<JsonNode> ended = post("/api/v1/sessions/" + sessionId + "/end", null, staff);

        assertThat(ended.getStatusCode()).as("end failed: %s", ended.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(ended.getBody().get("state").asText()).isEqualTo("CLOSED");
        assertThat(ended.getBody().get("netOutstanding").asInt()).isZero();
        // Nothing more was taken: the time was paid for at the counter, once.
        assertThat(countOf("transactions")).isEqualTo(transactionsAfterSale);
    }

    @Test
    @DisplayName("extra time on a seated token is ordinary billable time")
    void extraTimeIsBillable() {
        JsonNode sold = sellTicket("PS4", 1, "Nafis Iqbal");
        long sessionId = seat(sold.get("token").get("queueEntryId").asLong(), ps4)
                .get("session").get("id").asLong();

        ResponseEntity<JsonNode> extended = post("/api/v1/sessions/" + sessionId + "/blocks",
                Map.of("delta", 1), withKey(UUID.randomUUID().toString()));

        assertThat(extended.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(extended.getBody().get("blocks").asInt()).isEqualTo(2);
        assertThat(extended.getBody().get("paidBlocks").asInt()).isEqualTo(1);
        assertThat(extended.getBody().get("netOutstanding").asInt()).isEqualTo(PS4_HALF_HOUR);

        // And the seat cannot close over that half hour until somebody pays for it.
        assertErrorEnvelope(post("/api/v1/sessions/" + sessionId + "/end", null, staff),
                409, "SESSION_HAS_BALANCE");
    }

    // ---- a booking is seated by exactly the same code -------------------------------------------

    @Test
    @DisplayName("seating a checked-in booking flips the booking to USED")
    void bookingSeatFlipsToUsed() {
        long bookingId = book("Tanvir Ahmed", 2);
        JsonNode checkedIn = post("/api/v1/bookings/" + bookingId + "/check-in", null, staff)
                .getBody();
        long queueEntryId = checkedIn.get("token").get("queueEntryId").asLong();
        long saleTxId = fixtures.booking(bookingId).get("tx_id") instanceof Number txId
                ? txId.longValue()
                : 0L;

        JsonNode seated = seat(queueEntryId, ps5);

        long sessionId = seated.get("session").get("id").asLong();
        assertThat(fixtures.statusOf(bookingId)).isEqualTo("USED");
        assertThat(fixtures.tokenStatusOf(queueEntryId)).isEqualTo("SEATED");
        // The play half of the booking's sale is what the blocks carry — the package fee bought
        // the slot, not the time.
        assertThat(fixtures.blocksOf(sessionId)).hasSize(2)
                .allSatisfy(block -> assertThat(block)
                        .containsEntry("price", PS5_HALF_HOUR)
                        .containsEntry("paid_tx_id", saleTxId));
        assertThat(seated.get("session").get("netOutstanding").asInt()).isZero();
        assertThat(get("/api/v1/bookings?tab=history", staff).getBody().get(0).get("status").asText())
                .isEqualTo("USED");
    }

    @Test
    @DisplayName("POST /sessions with a bookingId is the same seat as the queue rail's")
    void sessionsEndpointSeatsABookingToo() {
        long bookingId = book("Rifat Hasan", 1);
        post("/api/v1/bookings/" + bookingId + "/check-in", null, staff);

        ResponseEntity<JsonNode> opened = post("/api/v1/sessions",
                Map.of("stationId", ps5, "bookingId", bookingId), staff);

        assertThat(opened.getStatusCode()).as("open failed: %s", opened.getBody())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(opened.getBody().get("paidBlocks").asInt()).isEqualTo(1);
        assertThat(opened.getBody().get("netOutstanding").asInt()).isZero();
        assertThat(fixtures.statusOf(bookingId)).isEqualTo("USED");
    }

    @Test
    @DisplayName("a booking that has not checked in has no token to seat")
    void unCheckedInBookingCannotBeSeated() {
        long bookingId = book("Nafis Iqbal", 1);

        assertErrorEnvelope(post("/api/v1/sessions",
                Map.of("stationId", ps5, "bookingId", bookingId), staff), 409, "CONFLICT");

        assertThat(fixtures.statusOf(bookingId)).isEqualTo("PAID");
        assertThat(countOf("sessions")).isZero();
    }

    // ---- the arrival prompt on the Floor card ---------------------------------------------------

    @Test
    @DisplayName("GET /stations offers the seat prompt for a checked-in booking, then drops it")
    void arrivalShowsOnTheStationCard() {
        long bookingId = book("Tanvir Ahmed", 2);
        assertThat(cardFor(ps5).get("arrival")).isNull();

        long queueEntryId = post("/api/v1/bookings/" + bookingId + "/check-in", null, staff)
                .getBody().get("token").get("queueEntryId").asLong();

        JsonNode arrival = cardFor(ps5).get("arrival");
        assertThat(arrival).isNotNull();
        assertThat(arrival.get("queueEntryId").asLong()).isEqualTo(queueEntryId);
        assertThat(arrival.get("bookingId").asLong()).isEqualTo(bookingId);
        assertThat(arrival.get("token").asInt()).isEqualTo(1);
        assertThat(arrival.get("name").asText()).isEqualTo("Tanvir Ahmed");
        assertThat(arrival.get("blocks").asInt()).isEqualTo(2);
        // Waiting is not playing: the card is still free to seat.
        assertThat(cardFor(ps5).get("floorState").asText()).isEqualTo("FREE");
        // A walk-up ticket belongs to no console, so it never shows as an arrival.
        assertThat(cardFor(ps4).get("arrival")).isNull();

        seat(queueEntryId, ps5);

        assertThat(cardFor(ps5).get("arrival")).isNull();
        assertThat(cardFor(ps5).get("floorState").asText()).isEqualTo("OPEN");
    }

    // ---- refusals ---------------------------------------------------------------------------------

    @Test
    @DisplayName("a PS5 ticket on a PS4 console is 409 CONSOLE_TYPE_MISMATCH, and seats nobody")
    void consoleTypeMismatch() {
        JsonNode sold = sellTicket("PS5", 2, "Rifat Hasan");
        long queueEntryId = sold.get("token").get("queueEntryId").asLong();

        assertErrorEnvelope(post("/api/v1/play-queue/" + queueEntryId + "/seat",
                Map.of("stationId", ps4), staff), 409, "CONSOLE_TYPE_MISMATCH");

        assertThat(fixtures.tokenStatusOf(queueEntryId)).isEqualTo("WAITING");
        assertThat(countOf("sessions")).isZero();
        assertThat(countOf("session_blocks")).isZero();

        // The same token still seats on the console it was sold for.
        assertThat(seat(queueEntryId, ps5).get("session").get("paidBlocks").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("a token is spent once — the second seat is 409 and loads no second helping")
    void tokenIsSpentOnce() {
        JsonNode sold = sellTicket("PS5", 2, "Rifat Hasan");
        long queueEntryId = sold.get("token").get("queueEntryId").asLong();
        seat(queueEntryId, ps5);
        post("/api/v1/sessions/" + longOf("SELECT id FROM sessions") + "/end", null, staff);

        assertErrorEnvelope(post("/api/v1/play-queue/" + queueEntryId + "/seat",
                Map.of("stationId", ps5), staff), 409, "CONFLICT");

        assertThat(countOf("sessions")).isEqualTo(1);
        assertThat(countOf("session_blocks")).isEqualTo(2);
    }

    @Test
    @DisplayName("a busy console refuses the token and leaves it waiting")
    void busyConsoleIsRefused() {
        floor.runningSessionOn(ps5, shiftId, 1, PS5_HALF_HOUR, 0);
        JsonNode sold = sellTicket("PS5", 1, "Rifat Hasan");
        long queueEntryId = sold.get("token").get("queueEntryId").asLong();

        assertErrorEnvelope(post("/api/v1/play-queue/" + queueEntryId + "/seat",
                Map.of("stationId", ps5), staff), 409, "STATION_BUSY");

        assertThat(fixtures.tokenStatusOf(queueEntryId)).isEqualTo("WAITING");
    }

    @Test
    @DisplayName("an unknown token is 404")
    void unknownToken() {
        assertErrorEnvelope(post("/api/v1/play-queue/999999/seat", Map.of("stationId", ps5), staff),
                404, "NOT_FOUND");
    }

    // ---- the rail ---------------------------------------------------------------------------------

    @Test
    @DisplayName("the rail lists who plays next in token order, then today's seated as history")
    void railOrder() {
        long first = sellTicket("PS5", 1, "First").get("token").get("queueEntryId").asLong();
        sellTicket("PS4", 1, "Second");
        sellTicket("PS5", 1, "Third");
        seat(first, ps5);

        JsonNode rail = get("/api/v1/play-queue", staff).getBody();

        assertThat(rail).hasSize(3);
        assertThat(rail).extracting(row -> row.get("playerName").asText(),
                        row -> row.get("status").asText())
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("Second", "WAITING"),
                        org.assertj.core.api.Assertions.tuple("Third", "WAITING"),
                        org.assertj.core.api.Assertions.tuple("First", "SEATED"));
    }

    // ---- day rollover (docs/bookings.md §7) --------------------------------------------------------

    @Test
    @DisplayName("the counter restarts at venue midnight and yesterday's token still seats")
    void dayRolloverKeepsOldTokensWorking() {
        LocalDate yesterday = LocalDate.now(VenueTime.ZONE).minusDays(1);
        clock.setToVenueTime(yesterday, EVENING);

        JsonNode stale = sellTicket("PS5", 2, "Rifat Hasan");
        long staleEntryId = stale.get("token").get("queueEntryId").asLong();
        assertThat(stale.get("token").get("tokenNo").asInt()).isEqualTo(1);
        assertThat(stale.get("token").get("tokenDate").asText()).isEqualTo(yesterday.toString());

        // Over midnight, into the next evening.
        clock.setToVenueTime(yesterday.plusDays(1), EVENING);
        JsonNode fresh = sellTicket("PS4", 1, "Nafis Iqbal");

        // The counter counts against the day, so it has restarted on its own — no job to run.
        assertThat(fresh.get("token").get("tokenNo").asInt()).isEqualTo(1);
        assertThat(fresh.get("token").get("tokenDate").asText())
                .isEqualTo(yesterday.plusDays(1).toString());
        assertThat(fixtures.nextTokenNoOn(yesterday)).isEqualTo(2);
        assertThat(fixtures.nextTokenNoOn(yesterday.plusDays(1))).isEqualTo(2);

        // Yesterday's customer is still owed their time: still on the rail, ahead of today's,
        // and carrying the date that explains why it reads #01 too.
        JsonNode rail = get("/api/v1/play-queue", staff).getBody();
        assertThat(rail).hasSize(2);
        assertThat(rail.get(0).get("id").asLong()).isEqualTo(staleEntryId);
        assertThat(rail.get(0).get("tokenDate").asText()).isEqualTo(yesterday.toString());

        // And it still seats — the entry id is the key, not the number on the paper.
        JsonNode seated = seat(staleEntryId, ps5);

        assertThat(seated.get("session").get("paidBlocks").asInt()).isEqualTo(2);
        assertThat(seated.get("session").get("netOutstanding").asInt()).isZero();
        assertThat(fixtures.blocksOf(seated.get("session").get("id").asLong()))
                .allSatisfy(block -> assertThat(block)
                        .containsEntry("paid_tx_id", stale.get("transactionId").asLong()));
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private JsonNode sellTicket(String consoleType, int blocks, String playerName) {
        ResponseEntity<JsonNode> sold = post("/api/v1/play-tickets",
                Map.of("consoleType", consoleType, "blocks", blocks, "playerName", playerName,
                        "method", "CASH"),
                withKey(UUID.randomUUID().toString()));
        assertThat(sold.getStatusCode()).as("sale failed: %s", sold.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return sold.getBody();
    }

    private JsonNode seat(long queueEntryId, Long stationId) {
        ResponseEntity<JsonNode> seated = post("/api/v1/play-queue/" + queueEntryId + "/seat",
                Map.of("stationId", stationId), staff);
        assertThat(seated.getStatusCode()).as("seat failed: %s", seated.getBody())
                .isEqualTo(HttpStatus.OK);
        return seated.getBody();
    }

    private long book(String name, int blocks) {
        Map<String, Object> request = new HashMap<>();
        request.put("stationId", ps5);
        request.put("name", name);
        request.put("startAt", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                .plusDays(1).toString());
        request.put("blocks", blocks);
        request.put("method", "CASH");
        ResponseEntity<JsonNode> created =
                post("/api/v1/bookings", request, withKey(UUID.randomUUID().toString()));
        assertThat(created.getStatusCode()).as("create failed: %s", created.getBody())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("booking").get("total").asInt())
                .isEqualTo(blocks * PS5_HALF_HOUR + PACKAGE_FEE);
        return created.getBody().get("booking").get("id").asLong();
    }

    private JsonNode cardFor(Long stationId) {
        return get("/api/v1/stations/" + stationId, staff).getBody();
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

    private long longOf(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }
}
