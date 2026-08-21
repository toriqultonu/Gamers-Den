package dev.gamersden.session.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamersden.support.AbstractApiIntegrationTest;
import dev.gamersden.support.FloorFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One live session per station — the rule the DDL enforces with
 * {@code CREATE UNIQUE INDEX one_live_session_per_station ON sessions (station_id) WHERE state
 * &lt;&gt; 'CLOSED'} (V001) and the service backs with 409 {@code STATION_BUSY}.
 *
 * <p>The service check alone would be a time-of-check/time-of-use hole: two cashiers on two
 * terminals can pass it in the same millisecond. So the index is proved directly against Postgres,
 * and the concurrent-seating case is proved through the API — whichever layer catches it, exactly
 * one session exists and the loser sees the same 409.
 */
class SessionOneLiveSessionIT extends AbstractApiIntegrationTest {

    private FloorFixtures floor;
    private Long stationId;
    private Long shiftId;

    @BeforeEach
    void seedFloor() {
        jdbc.update("DELETE FROM idempotency_keys");
        floor = new FloorFixtures(jdbc);
        stationId = createStation("PS5-01", "PS5");
        shiftId = floor.openShift(adminId, TERMINAL);
    }

    @Test
    @DisplayName("Postgres refuses a second live session on the same station")
    void theIndexRefusesASecondLiveRow() {
        insertSession("OPEN");

        assertThatThrownBy(() -> insertSession("OPEN"))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("one_live_session_per_station");

        assertThat(liveSessionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("every live state holds the seat — OPEN, RUNNING, PAUSED and LOCKED alike")
    void everyLiveStateHoldsTheSeat() {
        insertSession("RUNNING");

        for (String state : List.of("OPEN", "RUNNING", "PAUSED", "LOCKED")) {
            assertThatThrownBy(() -> insertSession(state))
                    .as("a %s session must not fit alongside a live one", state)
                    .isInstanceOf(DuplicateKeyException.class);
        }
    }

    @Test
    @DisplayName("the index is partial — closed sessions pile up freely")
    void closedSessionsDoNotHoldTheSeat() {
        insertSession("CLOSED");
        insertSession("CLOSED");
        insertSession("CLOSED");
        insertSession("RUNNING");

        assertThat(sessionCount()).isEqualTo(4);
        assertThat(liveSessionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("two cashiers seating the same console at once: one wins, one gets STATION_BUSY")
    void concurrentSeatingLeavesExactlyOneSession() throws Exception {
        HttpHeaders first = adminBearer();
        HttpHeaders second = adminBearer();
        CountDownLatch go = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<ResponseEntity<JsonNode>> a = pool.submit(seat(go, first));
            Future<ResponseEntity<JsonNode>> b = pool.submit(seat(go, second));
            go.countDown();

            List<ResponseEntity<JsonNode>> results = List.of(a.get(30, TimeUnit.SECONDS),
                    b.get(30, TimeUnit.SECONDS));

            assertThat(results).filteredOn(r -> r.getStatusCode() == HttpStatus.CREATED).hasSize(1);
            ResponseEntity<JsonNode> loser = results.stream()
                    .filter(r -> r.getStatusCode() != HttpStatus.CREATED)
                    .findFirst()
                    .orElseThrow();
            assertErrorEnvelope(loser, 409, "STATION_BUSY");
        }

        assertThat(liveSessionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a second station is a second seat — the rule is per station, not per floor")
    void otherStationsAreUnaffected() {
        Long other = createStation("PS4-01", "PS4");
        HttpHeaders staff = adminBearer();

        assertThat(post("/api/v1/sessions", Map.of("stationId", stationId), staff).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(post("/api/v1/sessions", Map.of("stationId", other), staff).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        assertThat(liveSessionCount()).isEqualTo(2);
    }

    private Callable<ResponseEntity<JsonNode>> seat(CountDownLatch go, HttpHeaders headers) {
        return () -> {
            go.await(30, TimeUnit.SECONDS);
            return post("/api/v1/sessions", Map.of("stationId", stationId), headers);
        };
    }

    private void insertSession(String state) {
        jdbc.update("INSERT INTO sessions (station_id, shift_id, state, ended_at) "
                        + "VALUES (?, ?, ?, CASE WHEN ? = 'CLOSED' THEN now() END)",
                stationId, shiftId, state, state);
    }

    private int sessionCount() {
        return jdbc.queryForObject("SELECT count(*) FROM sessions", Integer.class);
    }

    private int liveSessionCount() {
        return jdbc.queryForObject("SELECT count(*) FROM sessions WHERE state <> 'CLOSED'",
                Integer.class);
    }

    private Long createStation(String name, String consoleType) {
        ResponseEntity<JsonNode> created = post("/api/v1/stations",
                Map.of("name", name, "consoleType", consoleType), adminBearer());
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody().get("id").asLong();
    }
}
