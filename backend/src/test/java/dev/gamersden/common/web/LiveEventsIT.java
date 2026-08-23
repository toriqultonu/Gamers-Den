package dev.gamersden.common.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamersden.support.AbstractApiIntegrationTest;
import dev.gamersden.support.FloorFixtures;
import dev.gamersden.support.SseClient;
import dev.gamersden.support.TournamentFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /events} against a real socket (ARCHITECTURE.md §4.5, api-contract.md "Live updates
 * &amp; sync").
 *
 * <p>A subscriber is opened, the floor is then driven through the API, and what came down the
 * stream is asserted. Three properties are under test.
 *
 * <p><strong>Every change reaches the terminal.</strong> A block bought, a token sold and seated,
 * a booking checked in, a match extended — each lands as the event §4.5 names for it.
 *
 * <p><strong>The payload equals the GET shape.</strong> Not "looks like": each assertion checks
 * the event's body against the body of the corresponding GET, field for field, because that is
 * what lets the frontend write an event straight into the cache its polling fallback fills.
 *
 * <p><strong>Nothing is sent for work that rolled back.</strong> A refused block change tells the
 * floor nothing at all, which is the whole reason the events are emitted after commit rather than
 * at the call.
 */
class LiveEventsIT extends AbstractApiIntegrationTest {

    /** Generous on purpose: the assertion is about arrival, never about how fast. */
    private static final Duration ARRIVES = Duration.ofSeconds(10);

    private static final int ENTRY_FEE = 200;
    private static final int MATCH_MINUTES = 20;

    @LocalServerPort
    private int port;

    private FloorFixtures floor;
    private TournamentFixtures tournaments;
    private HttpHeaders staff;
    private SseClient events;
    private Long seat;
    private Long arenaA;
    private Long arenaB;

    @BeforeEach
    void seedFloorAndSubscribe() {
        floor = new FloorFixtures(jdbc);
        tournaments = new TournamentFixtures(jdbc);
        staff = adminBearer();
        seat = createStation("PS5-01", "PS5");
        arenaA = createStation("PS5-02", "PS5");
        arenaB = createStation("PS5-03", "PS5");
        floor.openShift(adminId, TERMINAL);
        events = SseClient.connect("http://localhost:" + port + "/api/v1/events",
                staff.getFirst(HttpHeaders.AUTHORIZATION).substring("Bearer ".length()));
    }

    @AfterEach
    void unsubscribe() {
        events.close();
    }

    // ---- station-update -------------------------------------------------------------------

    @Test
    @DisplayName("buying a block sends station-update carrying the Floor card")
    void blockChangeSendsStationUpdate() {
        long sessionId = openSession(seat);
        events.clear();

        buyBlock(sessionId);

        JsonNode card = events.await("station-update", ARRIVES).data();
        assertThat(card.get("id").asLong()).isEqualTo(seat);
        assertThat(card.get("name").asText()).isEqualTo("PS5-01");
        assertThat(card.get("session").get("id").asLong()).isEqualTo(sessionId);
        assertThat(card.get("session").get("blocks").asInt()).isEqualTo(1);
        // The payload is the GET shape, not a cousin of it (§4.5).
        assertThat(card).isEqualTo(get("/api/v1/stations/" + seat, staff).getBody());
    }

    @Test
    @DisplayName("a refused block change tells the floor nothing")
    void refusedChangeSendsNothing() {
        long sessionId = openSession(seat);
        events.clear();

        // Nothing bought yet, so there is no block to hand back: 409, and the transaction is gone.
        assertErrorEnvelope(post("/api/v1/sessions/" + sessionId + "/blocks",
                Map.of("delta", -1), withKey()), 409, "BLOCKS_CONSUMED");

        events.settle();
        assertThat(events.names()).isEmpty();
    }

    // ---- queue-update ---------------------------------------------------------------------

    @Test
    @DisplayName("selling a play ticket and seating it each send queue-update with the rail")
    void ticketSaleAndSeatSendQueueUpdate() {
        events.clear();

        JsonNode sold = sellTicket("PS5", 2, "Rifat Hasan");

        JsonNode railAfterSale = events.await("queue-update", ARRIVES).data();
        assertThat(railAfterSale).hasSize(1);
        assertThat(railAfterSale.get(0).get("tokenNo").asInt()).isEqualTo(1);
        assertThat(railAfterSale.get(0).get("status").asText()).isEqualTo("WAITING");
        assertThat(railAfterSale.get(0).get("playerName").asText()).isEqualTo("Rifat Hasan");
        assertThat(railAfterSale).isEqualTo(get("/api/v1/play-queue", staff).getBody());

        long queueEntryId = sold.get("token").get("queueEntryId").asLong();
        events.clear();

        ResponseEntity<JsonNode> seated = post("/api/v1/play-queue/" + queueEntryId + "/seat",
                Map.of("stationId", seat), staff);
        assertThat(seated.getStatusCode()).as("seat failed: %s", seated.getBody())
                .isEqualTo(HttpStatus.OK);

        JsonNode railAfterSeat = events.await("queue-update", ARRIVES).data();
        assertThat(railAfterSeat.get(0).get("status").asText()).isEqualTo("SEATED");
        // The seat is one transaction, so the console the token landed on moves with it (§5.9).
        JsonNode card = events.await("station-update", ARRIVES).data();
        assertThat(card.get("id").asLong()).isEqualTo(seat);
        assertThat(card.get("session").get("paidBlocks").asInt()).isEqualTo(2);
    }

    // ---- booking-update -------------------------------------------------------------------

    @Test
    @DisplayName("checking a booking in sends booking-update, its token, and the Floor card")
    void checkInSendsBookingUpdate() {
        long bookingId = book("Tanvir Ahmed", 2);
        events.clear();

        ResponseEntity<JsonNode> checkedIn =
                post("/api/v1/bookings/" + bookingId + "/check-in", null, staff);
        assertThat(checkedIn.getStatusCode()).as("check-in failed: %s", checkedIn.getBody())
                .isEqualTo(HttpStatus.OK);

        JsonNode booking = events.await("booking-update", ARRIVES).data();
        assertThat(booking.get("id").asLong()).isEqualTo(bookingId);
        assertThat(booking.get("status").asText()).isEqualTo("ARRIVED");
        assertThat(booking.get("tokenNo").asInt()).isEqualTo(1);
        assertThat(booking).isEqualTo(get("/api/v1/bookings/" + bookingId, staff).getBody());

        // The token that was just printed is on the rail, and the console offers the seat prompt.
        assertThat(events.await("queue-update", ARRIVES).data().get(0).get("source").asText())
                .isEqualTo("BOOKING");
        assertThat(events.await("station-update", ARRIVES).data()
                .get("arrival").get("bookingId").asLong()).isEqualTo(bookingId);
    }

    // ---- tournament-update ----------------------------------------------------------------

    @Test
    @DisplayName("extending a match sends tournament-update and moves the console's card with it")
    void extendSendsTournamentUpdate() {
        long tournamentId = liveTournament();
        long matchId = firstPlayableMatch(tournamentId);
        JsonNode started = post("/api/v1/tournaments/" + tournamentId + "/matches/" + matchId
                + "/start", null, staff).getBody();
        long stationId = started.get("stationId").asLong();
        events.clear();

        ResponseEntity<JsonNode> extended = post("/api/v1/tournaments/" + tournamentId + "/matches/"
                + matchId + "/extend", Map.of("minutes", 5), staff);
        assertThat(extended.getStatusCode()).as("extend failed: %s", extended.getBody())
                .isEqualTo(HttpStatus.OK);

        JsonNode detail = events.await("tournament-update", ARRIVES).data();
        assertThat(detail.get("tournament").get("id").asLong()).isEqualTo(tournamentId);
        JsonNode match = bracketMatch(detail, matchId);
        assertThat(match.get("remainingSeconds").asLong())
                .isGreaterThan(MATCH_MINUTES * 60L)
                .isLessThanOrEqualTo((MATCH_MINUTES + 5) * 60L);

        // §4.5 puts match timers on station-update too: the Floor card carries the same countdown.
        JsonNode card = events.await("station-update", ARRIVES).data();
        assertThat(card.get("id").asLong()).isEqualTo(stationId);
        assertThat(card.get("floorState").asText()).isEqualTo("RESERVED");
        assertThat(card.get("match").get("matchId").asLong()).isEqualTo(matchId);
    }

    // ---- helpers ----------------------------------------------------------------------------

    private long openSession(Long stationId) {
        ResponseEntity<JsonNode> opened =
                post("/api/v1/sessions", Map.of("stationId", stationId), staff);
        assertThat(opened.getStatusCode()).as("open failed: %s", opened.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return opened.getBody().get("id").asLong();
    }

    private void buyBlock(long sessionId) {
        ResponseEntity<JsonNode> bought = post("/api/v1/sessions/" + sessionId + "/blocks",
                Map.of("delta", 1), withKey());
        assertThat(bought.getStatusCode()).as("block failed: %s", bought.getBody())
                .isEqualTo(HttpStatus.OK);
    }

    private JsonNode sellTicket(String consoleType, int blocks, String playerName) {
        ResponseEntity<JsonNode> sold = post("/api/v1/play-tickets",
                Map.of("consoleType", consoleType, "blocks", blocks, "playerName", playerName,
                        "method", "CASH"), withKey());
        assertThat(sold.getStatusCode()).as("sale failed: %s", sold.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return sold.getBody();
    }

    private long book(String name, int blocks) {
        Map<String, Object> request = new HashMap<>();
        request.put("stationId", seat);
        request.put("name", name);
        request.put("startAt", OffsetDateTime.now().plusDays(1).toString());
        request.put("blocks", blocks);
        request.put("method", "CASH");
        ResponseEntity<JsonNode> created = post("/api/v1/bookings", request, withKey());
        assertThat(created.getStatusCode()).as("booking failed: %s", created.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return created.getBody().get("booking").get("id").asLong();
    }

    /** Four entries against a cap of four: the bracket draws itself and the event goes LIVE. */
    private long liveTournament() {
        Long id = tournaments.openTournament("Friday FIFA", ENTRY_FEE, 4, adminId);
        tournaments.block(id, arenaA);
        tournaments.block(id, arenaB);
        List<Map<String, Object>> lines = new ArrayList<>();
        for (String player : List.of("Rifat", "Nafis", "Tanvir", "Shuvo")) {
            lines.add(Map.of("tournamentId", id, "playerName", player));
        }
        ResponseEntity<JsonNode> settled = post("/api/v1/payments", Map.of(
                "target", Map.of(),
                "tournamentEntries", lines,
                "splits", List.of(Map.of("method", "CASH", "amount", 4 * ENTRY_FEE))), withKey());
        assertThat(settled.getStatusCode()).as("entry sale failed: %s", settled.getBody())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(tournaments.statusOf(id)).isEqualTo("LIVE");
        return id;
    }

    private long firstPlayableMatch(long tournamentId) {
        return tournaments.matchesOf(tournamentId).stream()
                .filter(match -> match.get("entry_a") != null && match.get("entry_b") != null
                        && match.get("winner_entry") == null)
                .map(match -> (Long) match.get("id"))
                .findFirst().orElseThrow();
    }

    private static JsonNode bracketMatch(JsonNode detail, long matchId) {
        for (JsonNode match : detail.get("bracket")) {
            if (match.get("id").asLong() == matchId) {
                return match;
            }
        }
        throw new AssertionError("match " + matchId + " is not in the pushed bracket");
    }

    private Long createStation(String name, String consoleType) {
        return post("/api/v1/stations", Map.of("name", name, "consoleType", consoleType), staff)
                .getBody().get("id").asLong();
    }

    private HttpHeaders withKey() {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(staff);
        headers.add("Idempotency-Key", UUID.randomUUID().toString());
        return headers;
    }
}
