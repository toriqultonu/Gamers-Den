package dev.gamersden.session.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamersden.session.domain.SessionService;
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
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /sessions} end to end against a real Postgres and a real filter chain: the state machine,
 * the block ledger, the clock and the net-outstanding end guard (api-contract.md, Sessions).
 *
 * <p>The venue clock is the test's to move ({@link MutableClockConfig}) — every countdown in the
 * application derives from that bean, never from a request body, so "play for ten minutes" here is
 * one call to {@link #advance}.
 */
@Import(MutableClockConfig.class)
class SessionLifecycleIT extends AbstractApiIntegrationTest {

    private static final long SETTLE_TX = 9_001L;

    @Autowired
    private MutableClock clock;

    /** The sweeper is driven by hand here — the @Scheduled trigger is off under the test profile. */
    @Autowired
    private SessionService sessionExpiry;

    private FloorFixtures floor;
    private HttpHeaders staff;
    private Long stationId;

    @BeforeEach
    void seedFloor() {
        jdbc.update("DELETE FROM idempotency_keys");
        clock.resetToNow();
        floor = new FloorFixtures(jdbc);
        staff = adminBearer();
        stationId = createStation("PS5-01", "PS5");
        floor.openShift(adminId, TERMINAL);
    }

    // ---- POST /sessions -------------------------------------------------------------------

    @Test
    @DisplayName("seating a customer opens a session with no time on it")
    void openingASeat() {
        ResponseEntity<JsonNode> created = openSession();

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode session = created.getBody();
        assertThat(session.get("state").asText()).isEqualTo("OPEN");
        assertThat(session.get("stationId").asLong()).isEqualTo(stationId);
        assertThat(session.get("blocks").asInt()).isZero();
        assertThat(session.get("remainingSeconds").asLong()).isZero();
        assertThat(session.get("netOutstanding").asInt()).isZero();
        assertThat(session.get("serverTime").asText()).isNotBlank();
        // non_null inclusion: a walk-in carries no member and no token.
        assertThat(session.has("memberId")).isFalse();
        assertThat(session.has("queueEntryId")).isFalse();
        assertThat(session.has("endedAt")).isFalse();

        assertThat(floor.stateOf(session.get("id").asLong())).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("the seat shows on the Floor card the moment it is taken")
    void theFloorCardFollowsTheSession() {
        long id = openSessionId();

        JsonNode card = get("/api/v1/stations", staff).getBody().get(0);
        assertThat(card.get("floorState").asText()).isEqualTo("OPEN");
        assertThat(card.get("session").get("id").asLong()).isEqualTo(id);
    }

    @Test
    @DisplayName("a second session on an occupied seat is 409 STATION_BUSY")
    void aBusySeatIsRefused() {
        openSessionId();

        assertErrorEnvelope(post("/api/v1/sessions", Map.of("stationId", stationId), staff),
                409, "STATION_BUSY");
        assertThat(liveSessionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a seat frees up again once its session is closed")
    void aClosedSeatIsFree() {
        long first = openSessionId();
        assertThat(end(first).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(openSession().getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("a session needs an open shift to belong to")
    void noShiftNoSession() {
        jdbc.update("DELETE FROM shifts");

        assertErrorEnvelope(post("/api/v1/sessions", Map.of("stationId", stationId), staff),
                409, "CONFLICT");
    }

    @Test
    @DisplayName("prepaid-seat handles resolve through the queue package — no token, no seat")
    void unknownTokensAre404UntilB16() {
        assertErrorEnvelope(post("/api/v1/sessions",
                Map.of("stationId", stationId, "bookingId", 42), staff), 404, "NOT_FOUND");
        assertErrorEnvelope(post("/api/v1/sessions",
                Map.of("stationId", stationId, "queueEntryId", 42), staff), 404, "NOT_FOUND");
        assertErrorEnvelope(post("/api/v1/sessions",
                Map.of("stationId", stationId, "bookingId", 42, "queueEntryId", 7), staff),
                400, "VALIDATION_FAILED");
    }

    @Test
    @DisplayName("an unknown station, a missing stationId and a station under maintenance")
    void openRejections() {
        assertErrorEnvelope(post("/api/v1/sessions", Map.of("stationId", 999999), staff),
                404, "NOT_FOUND");
        assertErrorEnvelope(post("/api/v1/sessions", Collections.emptyMap(), staff),
                400, "VALIDATION_FAILED");

        patch("/api/v1/stations/" + stationId, Map.of("status", "MAINTENANCE"), adminBearer());
        assertErrorEnvelope(post("/api/v1/sessions", Map.of("stationId", stationId), staff),
                409, "CONFLICT");
    }

    // ---- POST /sessions/{id}/blocks --------------------------------------------------------

    @Test
    @DisplayName("a block snapshots the live rate and adds thirty minutes")
    void buyingBlocks() {
        long id = openSessionId();

        JsonNode after = blocks(id, 1).getBody();
        assertThat(after.get("blocks").asInt()).isEqualTo(1);
        assertThat(after.get("purchasedSeconds").asLong()).isEqualTo(1800);
        assertThat(after.get("remainingSeconds").asLong()).isEqualTo(1800);
        assertThat(after.get("unpaidBlocks").asInt()).isEqualTo(1);
        assertThat(after.get("netOutstanding").asInt()).isEqualTo(after.get("gamingDue").asInt());

        blocks(id, 1);
        assertThat(get("/api/v1/sessions/" + id, staff).getBody().get("blocks").asInt()).isEqualTo(2);
        assertThat(floor.blockPricesOf(id)).allMatch(price -> price == 80 || price == 60);
    }

    @Test
    @DisplayName("the same Idempotency-Key never sells the same half hour twice")
    void blocksAreIdempotent() {
        long id = openSessionId();
        HttpHeaders key = keyed(UUID.randomUUID().toString());

        ResponseEntity<JsonNode> first = post("/api/v1/sessions/" + id + "/blocks",
                Map.of("delta", 1), key);
        ResponseEntity<JsonNode> retry = post("/api/v1/sessions/" + id + "/blocks",
                Map.of("delta", 1), key);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
        assertThat(retry.getBody()).isEqualTo(first.getBody());
        assertThat(floor.blockPricesOf(id)).hasSize(1);
    }

    @Test
    @DisplayName("the blocks route is on the idempotency list — no key, no sale")
    void blocksWithoutAKeyAreRejected() {
        long id = openSessionId();

        assertErrorEnvelope(post("/api/v1/sessions/" + id + "/blocks", Map.of("delta", 1), staff),
                400, "VALIDATION_FAILED");
        assertThat(floor.blockPricesOf(id)).isEmpty();
    }

    @Test
    @DisplayName("only +1 and -1 are blocks")
    void deltaIsPlusOrMinusOne() {
        long id = openSessionId();

        assertErrorEnvelope(blocks(id, 0), 400, "VALIDATION_FAILED");
        assertErrorEnvelope(blocks(id, 2), 400, "VALIDATION_FAILED");
        assertErrorEnvelope(post("/api/v1/sessions/" + id + "/blocks",
                Collections.emptyMap(), keyed(UUID.randomUUID().toString())), 400, "VALIDATION_FAILED");
    }

    @Test
    @DisplayName("returning an unplayed block gives the time back and keeps the ledger row")
    void returningABlock() {
        long id = openSessionId();
        blocks(id, 1);
        blocks(id, 1);

        JsonNode after = blocks(id, -1).getBody();

        assertThat(after.get("blocks").asInt()).isEqualTo(1);
        assertThat(after.get("remainingSeconds").asLong()).isEqualTo(1800);
        assertThat(floor.blockPricesOf(id)).hasSize(1);
        assertThat(floor.removedBlockCountOf(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("time already played cannot be returned — 409 BLOCKS_CONSUMED")
    void playedTimeCannotBeReturned() {
        long id = openSessionId();
        blocks(id, 1);
        blocks(id, 1);
        clock(id, "START");
        // 40 minutes played: the first block is gone, the second is half spent.
        advance(Duration.ofMinutes(40));

        assertErrorEnvelope(blocks(id, -1), 409, "BLOCKS_CONSUMED");
        assertThat(floor.blockPricesOf(id)).hasSize(2);
    }

    @Test
    @DisplayName("a block that has been paid for cannot be returned — 409 BLOCKS_CONSUMED")
    void paidTimeCannotBeReturned() {
        long id = openSessionId();
        blocks(id, 1);
        floor.markBlocksPaid(id, SETTLE_TX);

        assertErrorEnvelope(blocks(id, -1), 409, "BLOCKS_CONSUMED");
        assertThat(floor.blockPricesOf(id)).hasSize(1);
    }

    @Test
    @DisplayName("a session with no blocks has nothing to return")
    void nothingToReturn() {
        long id = openSessionId();

        assertErrorEnvelope(blocks(id, -1), 409, "BLOCKS_CONSUMED");
    }

    @Test
    @DisplayName("a settle mid-session leaves paid blocks; the next block is unpaid again")
    void blocksPaidMidSessionStayPaid() {
        long id = openSessionId();
        blocks(id, 1);
        blocks(id, 1);
        floor.markBlocksPaid(id, SETTLE_TX);

        JsonNode after = blocks(id, 1).getBody();

        assertThat(after.get("blocks").asInt()).isEqualTo(3);
        assertThat(after.get("paidBlocks").asInt()).isEqualTo(2);
        assertThat(after.get("unpaidBlocks").asInt()).isEqualTo(1);
        assertThat(after.get("netOutstanding").asInt()).isEqualTo(after.get("gamingDue").asInt());
    }

    // ---- POST /sessions/{id}/clock ---------------------------------------------------------

    @Test
    @DisplayName("starting without time is 409 NO_BLOCKS")
    void noTimeNoClock() {
        long id = openSessionId();

        assertErrorEnvelope(clock(id, "START"), 409, "NO_BLOCKS");
        assertThat(floor.stateOf(id)).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("start, pause, sit idle, resume — only played minutes are consumed")
    void theClockRunsPausesAndResumes() {
        long id = openSessionId();
        blocks(id, 1);
        blocks(id, 1);

        JsonNode started = clock(id, "START").getBody();
        assertThat(started.get("state").asText()).isEqualTo("RUNNING");
        assertThat(started.get("remainingSeconds").asLong()).isEqualTo(3600);

        advance(Duration.ofMinutes(10));
        JsonNode paused = clock(id, "PAUSE").getBody();
        assertThat(paused.get("state").asText()).isEqualTo("PAUSED");
        assertThat(paused.get("consumedSeconds").asLong()).isEqualTo(600);
        assertThat(paused.get("remainingSeconds").asLong()).isEqualTo(3000);

        // An hour on the shelf: a paused countdown does not move.
        advance(Duration.ofMinutes(60));
        JsonNode stillPaused = get("/api/v1/sessions/" + id, staff).getBody();
        assertThat(stillPaused.get("state").asText()).isEqualTo("PAUSED");
        assertThat(stillPaused.get("remainingSeconds").asLong()).isEqualTo(3000);

        clock(id, "RESUME");
        advance(Duration.ofMinutes(5));
        JsonNode running = get("/api/v1/sessions/" + id, staff).getBody();
        assertThat(running.get("state").asText()).isEqualTo("RUNNING");
        assertThat(running.get("consumedSeconds").asLong()).isEqualTo(900);
        assertThat(running.get("remainingSeconds").asLong()).isEqualTo(2700);

        // The banked half is what survives a restart: consumed_sec holds the paused stretches.
        assertThat(floor.consumedSecOf(id)).isEqualTo(600);
    }

    @Test
    @DisplayName("every illegal clock action is 409 CONFLICT and changes nothing")
    void illegalClockActions() {
        long id = openSessionId();
        blocks(id, 1);

        // OPEN: only START.
        assertErrorEnvelope(clock(id, "PAUSE"), 409, "CONFLICT");
        assertErrorEnvelope(clock(id, "RESUME"), 409, "CONFLICT");
        assertThat(floor.stateOf(id)).isEqualTo("OPEN");

        clock(id, "START");
        // RUNNING: only PAUSE.
        assertErrorEnvelope(clock(id, "START"), 409, "CONFLICT");
        assertErrorEnvelope(clock(id, "RESUME"), 409, "CONFLICT");
        assertThat(floor.stateOf(id)).isEqualTo("RUNNING");

        clock(id, "PAUSE");
        // PAUSED: only RESUME.
        assertErrorEnvelope(clock(id, "START"), 409, "CONFLICT");
        assertErrorEnvelope(clock(id, "PAUSE"), 409, "CONFLICT");
        assertThat(floor.stateOf(id)).isEqualTo("PAUSED");
    }

    @Test
    @DisplayName("an unknown clock action is a 400, not a state-machine question")
    void unknownClockAction() {
        long id = openSessionId();

        assertErrorEnvelope(post("/api/v1/sessions/" + id + "/clock",
                Map.of("action", "REWIND"), staff), 400, "VALIDATION_FAILED");
        assertErrorEnvelope(post("/api/v1/sessions/" + id + "/clock",
                Collections.emptyMap(), staff), 400, "VALIDATION_FAILED");
    }

    // ---- LOCKED --------------------------------------------------------------------------

    @Test
    @DisplayName("time running out locks the seat, and an overrun is not billed as played")
    void runningOutOfTimeLocksTheSeat() {
        long id = openSessionId();
        blocks(id, 1);
        clock(id, "START");

        advance(Duration.ofMinutes(45));
        JsonNode locked = get("/api/v1/sessions/" + id, staff).getBody();

        assertThat(locked.get("state").asText()).isEqualTo("LOCKED");
        assertThat(locked.get("remainingSeconds").asLong()).isZero();
        // Capped at the 30 minutes actually bought, not the 45 that elapsed.
        assertThat(locked.get("consumedSeconds").asLong()).isEqualTo(1800);
        assertThat(floor.stateOf(id)).isEqualTo("LOCKED");
        assertThat(floor.consumedSecOf(id)).isEqualTo(1800);

        assertThat(get("/api/v1/stations", staff).getBody().get(0).get("floorState").asText())
                .isEqualTo("LOCKED");
    }

    @Test
    @DisplayName("a locked seat cannot be started or resumed — 409 CONFLICT")
    void aLockedSeatHasNoClock() {
        long id = lockedSession();

        assertErrorEnvelope(clock(id, "RESUME"), 409, "CONFLICT");
        assertErrorEnvelope(clock(id, "START"), 409, "CONFLICT");
    }

    @Test
    @DisplayName("buying time on a locked seat unlocks it, paused, ready to resume")
    void buyingTimeUnlocksALockedSeat() {
        long id = lockedSession();

        JsonNode unlocked = blocks(id, 1).getBody();
        assertThat(unlocked.get("state").asText()).isEqualTo("PAUSED");
        assertThat(unlocked.get("remainingSeconds").asLong()).isEqualTo(1800);

        JsonNode resumed = clock(id, "RESUME").getBody();
        assertThat(resumed.get("state").asText()).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("the sweeper persists the lock on an idle floor")
    void theSweeperLocksExhaustedSessions() {
        long id = openSessionId();
        blocks(id, 1);
        clock(id, "START");
        clock.advance(Duration.ofMinutes(31));
        assertThat(floor.stateOf(id)).isEqualTo("RUNNING");

        assertThat(sessionExpiry.lockExhaustedSessions()).isEqualTo(1);

        assertThat(floor.stateOf(id)).isEqualTo("LOCKED");
        // Idempotent: a second sweep has nothing left to do.
        assertThat(sessionExpiry.lockExhaustedSessions()).isZero();
    }

    // ---- POST /sessions/{id}/end -----------------------------------------------------------

    @Test
    @DisplayName("unpaid blocks block the end — 409 SESSION_HAS_BALANCE with the breakdown")
    void unpaidBlocksBlockTheEnd() {
        long id = openSessionId();
        blocks(id, 1);
        blocks(id, 1);
        int due = get("/api/v1/sessions/" + id, staff).getBody().get("gamingDue").asInt();

        ResponseEntity<JsonNode> refused = end(id);

        assertErrorEnvelope(refused, 409, "SESSION_HAS_BALANCE");
        JsonNode details = refused.getBody().get("error").get("details");
        assertThat(details.get("netOutstanding").asInt()).isEqualTo(due);
        assertThat(details.get("gamingDue").asInt()).isEqualTo(due);
        assertThat(details.get("fnbDue").asInt()).isZero();
        assertThat(floor.stateOf(id)).isNotEqualTo("CLOSED");
    }

    @Test
    @DisplayName("an unsettled cart blocks the end even when every block is paid")
    void anUnsettledCartBlocksTheEnd() {
        long id = openSessionId();
        blocks(id, 1);
        floor.markBlocksPaid(id, SETTLE_TX);
        Long cartId = floor.unsettledCartOn(id, "Mountain Dew", 60, 3);

        ResponseEntity<JsonNode> refused = end(id);
        assertErrorEnvelope(refused, 409, "SESSION_HAS_BALANCE");
        assertThat(refused.getBody().get("error").get("details").get("fnbDue").asInt()).isEqualTo(180);

        floor.settleCart(cartId);
        assertThat(end(id).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("prepaid blocks count as settled — a prepaid seat closes without paying twice")
    void aPrepaidSeatEndsWithoutASecondPayment() {
        long id = openSessionId();
        // What B16's seat transaction inserts: four blocks born carrying the booking sale's tx.
        floor.prepaidBlocksOn(id, 4, 80, 5_150L);

        JsonNode session = get("/api/v1/sessions/" + id, staff).getBody();
        assertThat(session.get("paidBlocks").asInt()).isEqualTo(4);
        assertThat(session.get("netOutstanding").asInt()).isZero();

        assertThat(end(id).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("extra time on a prepaid seat is ordinary billable time")
    void extraTimeOnAPrepaidSeatIsBillable() {
        long id = openSessionId();
        floor.prepaidBlocksOn(id, 4, 80, 5_150L);
        blocks(id, 1);

        assertErrorEnvelope(end(id), 409, "SESSION_HAS_BALANCE");
    }

    @Test
    @DisplayName("a settled session closes, banks its played time and frees the seat")
    void endingASettledSession() {
        long id = openSessionId();
        blocks(id, 1);
        blocks(id, 1);
        clock(id, "START");
        advance(Duration.ofMinutes(20));
        floor.markBlocksPaid(id, SETTLE_TX);

        JsonNode closed = end(id).getBody();

        assertThat(closed.get("state").asText()).isEqualTo("CLOSED");
        assertThat(closed.get("consumedSeconds").asLong()).isEqualTo(1200);
        assertThat(closed.get("endedAt").asText()).isNotBlank();
        assertThat(floor.stateOf(id)).isEqualTo("CLOSED");
        assertThat(floor.consumedSecOf(id)).isEqualTo(1200);
        assertThat(get("/api/v1/stations", staff).getBody().get(0).get("floorState").asText())
                .isEqualTo("FREE");
    }

    @Test
    @DisplayName("an untimed seat can simply be given up")
    void endingAnEmptySession() {
        long id = openSessionId();

        assertThat(end(id).getBody().get("state").asText()).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("a locked seat ends once the bill is settled")
    void endingALockedSession() {
        long id = lockedSession();
        floor.markBlocksPaid(id, SETTLE_TX);

        assertThat(end(id).getBody().get("state").asText()).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("a closed session is history — nothing moves out of it")
    void aClosedSessionIsFrozen() {
        long id = openSessionId();
        end(id);

        assertErrorEnvelope(end(id), 409, "CONFLICT");
        assertErrorEnvelope(blocks(id, 1), 409, "CONFLICT");
        assertErrorEnvelope(clock(id, "START"), 409, "CONFLICT");
    }

    // ---- reads ----------------------------------------------------------------------------

    @Test
    @DisplayName("the active list holds live seats, the closed list holds history")
    void listingSessions() {
        long first = openSessionId();
        end(first);
        Long second = createStation("PS5-02", "PS5");
        long live = post("/api/v1/sessions", Map.of("stationId", second), staff)
                .getBody().get("id").asLong();

        JsonNode active = get("/api/v1/sessions?active=true", staff).getBody();
        assertThat(active).hasSize(1);
        assertThat(active.get(0).get("id").asLong()).isEqualTo(live);

        JsonNode closed = get("/api/v1/sessions?active=false", staff).getBody();
        assertThat(closed).hasSize(1);
        assertThat(closed.get(0).get("id").asLong()).isEqualTo(first);

        // active=true is the default the Floor calls with.
        assertThat(get("/api/v1/sessions", staff).getBody()).hasSize(1);
    }

    @Test
    @DisplayName("an unknown session is a 404 on every route")
    void unknownSession() {
        assertErrorEnvelope(get("/api/v1/sessions/999999", staff), 404, "NOT_FOUND");
        assertErrorEnvelope(end(999999L), 404, "NOT_FOUND");
        assertErrorEnvelope(clock(999999L, "START"), 404, "NOT_FOUND");
        assertErrorEnvelope(blocks(999999L, 1), 404, "NOT_FOUND");
    }

    @Test
    @DisplayName("the floor is not open to the street")
    void noTokenIs401() {
        assertErrorEnvelope(get("/api/v1/sessions", null), 401, "UNAUTHORIZED");
        assertErrorEnvelope(post("/api/v1/sessions", Map.of("stationId", stationId), null),
                401, "UNAUTHORIZED");
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** Pushes the venue clock and re-issues the access token, which expires against that clock. */
    private void advance(Duration amount) {
        clock.advance(amount);
        staff = adminBearer();
    }

    private ResponseEntity<JsonNode> openSession() {
        return post("/api/v1/sessions", Map.of("stationId", stationId), staff);
    }

    private long openSessionId() {
        ResponseEntity<JsonNode> created = openSession();
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody().get("id").asLong();
    }

    /** A seat that has played out its single block: RUNNING to LOCKED, nothing left to run. */
    private long lockedSession() {
        long id = openSessionId();
        blocks(id, 1);
        clock(id, "START");
        advance(Duration.ofMinutes(31));
        assertThat(get("/api/v1/sessions/" + id, staff).getBody().get("state").asText())
                .isEqualTo("LOCKED");
        return id;
    }

    private ResponseEntity<JsonNode> blocks(long sessionId, int delta) {
        return post("/api/v1/sessions/" + sessionId + "/blocks", Map.of("delta", delta),
                keyed(UUID.randomUUID().toString()));
    }

    private ResponseEntity<JsonNode> clock(long sessionId, String action) {
        return post("/api/v1/sessions/" + sessionId + "/clock", Map.of("action", action), staff);
    }

    private ResponseEntity<JsonNode> end(long sessionId) {
        return post("/api/v1/sessions/" + sessionId + "/end", Collections.emptyMap(), staff);
    }

    private HttpHeaders keyed(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(staff);
        headers.add("Idempotency-Key", key);
        return headers;
    }

    private Long createStation(String name, String consoleType) {
        ResponseEntity<JsonNode> created = post("/api/v1/stations",
                Map.of("name", name, "consoleType", consoleType), adminBearer());
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody().get("id").asLong();
    }

    private int liveSessionCount() {
        return jdbc.queryForObject("SELECT count(*) FROM sessions WHERE state <> 'CLOSED'",
                Integer.class);
    }
}
