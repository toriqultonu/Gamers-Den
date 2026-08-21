package dev.gamersden.common.idempotency;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamersden.common.error.ConflictException;
import dev.gamersden.common.error.ErrorCode;
import dev.gamersden.support.AbstractApiIntegrationTest;
import dev.gamersden.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The idempotency contract (api-contract.md §1) against a real filter chain and a real Postgres.
 *
 * <p>The money endpoints it actually guards do not exist yet (B05+), so the routes here are probe
 * controllers registered under a test-only {@link IdempotencyPolicy}; that the production list
 * names the right eight routes is asserted in {@link IdempotencyPolicyTest}.
 */
class IdempotencyFilterIT extends AbstractApiIntegrationTest {

    private static final String PROBE = "/api/v1/idempotency-probe";
    private static final String PROBE_ALT = "/api/v1/idempotency-probe-alt";
    private static final String PROBE_REJECT = "/api/v1/idempotency-probe-reject";
    private static final String UNGUARDED = "/api/v1/idempotency-open-probe";

    private static final Map<String, Object> SETTLE = Map.of("amount", 500, "method", "CASH");
    private static final Map<String, Object> MUTATED = Map.of("amount", 5000, "method", "CASH");

    @Autowired
    private MutableClock clock;

    @Autowired
    private ProbeController probe;

    @Autowired
    private IdempotencyStore store;

    @BeforeEach
    void resetIdempotencyState() {
        jdbc.update("DELETE FROM idempotency_keys");
        probe.reset();
        clock.resetToNow();
    }

    // ---- the key itself -------------------------------------------------------------------

    @Test
    @DisplayName("a guarded route without a key is rejected before the controller runs")
    void missingKeyIsRejected() {
        ResponseEntity<JsonNode> response = post(PROBE, SETTLE, adminBearer());

        assertErrorEnvelope(response, 400, ErrorCode.VALIDATION_FAILED.name());
        assertThat(probe.calls()).isZero();
        assertThat(storedKeys()).isZero();
    }

    @Test
    @DisplayName("a key that is not a UUID is rejected")
    void nonUuidKeyIsRejected() {
        ResponseEntity<JsonNode> response = post(PROBE, SETTLE, keyed("not-a-uuid"));

        assertErrorEnvelope(response, 400, ErrorCode.VALIDATION_FAILED.name());
        assertThat(probe.calls()).isZero();
    }

    @Test
    @DisplayName("authentication still wins over the missing key")
    void unauthenticatedCallerGets401NotAMissingKey() {
        ResponseEntity<JsonNode> response = post(PROBE, SETTLE, null);

        assertErrorEnvelope(response, 401, ErrorCode.UNAUTHORIZED.name());
        assertThat(probe.calls()).isZero();
    }

    @Test
    @DisplayName("an unguarded route neither demands nor stores a key")
    void unguardedRouteIsUntouched() {
        ResponseEntity<JsonNode> response = post(UNGUARDED, SETTLE, adminBearer());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(replayedHeader(response)).isNull();
        assertThat(storedKeys()).isZero();
    }

    // ---- store, replay, mismatch ------------------------------------------------------------

    @Test
    @DisplayName("the first call runs and its response is stored")
    void firstCallIsStored() {
        String key = UUID.randomUUID().toString();

        ResponseEntity<JsonNode> first = post(PROBE, SETTLE, keyed(key));

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(first.getBody().get("call").asInt()).isEqualTo(1);
        assertThat(replayedHeader(first)).isNull();
        assertThat(probe.calls()).isEqualTo(1);

        Map<String, Object> row = storedRow(key);
        assertThat(row.get("status_code")).isEqualTo(201);
        assertThat(row.get("request_hash")).asString().hasSize(64);
        assertThat(row.get("call")).isEqualTo("1");
        assertThat(row.get("amount")).isEqualTo("500");
    }

    @Test
    @DisplayName("an identical retry replays the stored response with Idempotency-Replayed: true")
    void identicalRetryIsReplayed() {
        String key = UUID.randomUUID().toString();

        ResponseEntity<JsonNode> first = post(PROBE, SETTLE, keyed(key));
        ResponseEntity<JsonNode> retry = post(PROBE, SETTLE, keyed(key));

        assertThat(retry.getStatusCode()).isEqualTo(first.getStatusCode());
        assertThat(retry.getBody()).isEqualTo(first.getBody());
        assertThat(replayedHeader(retry)).isEqualTo("true");
        assertThat(retry.getHeaders().getContentType().toString()).startsWith("application/json");
        assertThat(probe.calls()).as("the controller must run exactly once").isEqualTo(1);
        assertThat(storedKeys()).isEqualTo(1);
    }

    @Test
    @DisplayName("a mutated body under the same key is a 409 IDEMPOTENCY_REPLAY")
    void mutatedBodyUnderTheSameKeyConflicts() {
        String key = UUID.randomUUID().toString();

        post(PROBE, SETTLE, keyed(key));
        ResponseEntity<JsonNode> mutated = post(PROBE, MUTATED, keyed(key));

        assertErrorEnvelope(mutated, 409, ErrorCode.IDEMPOTENCY_REPLAY.name());
        assertThat(probe.calls()).as("the mutated request must never reach the controller").isEqualTo(1);
        assertThat(storedRow(key).get("amount")).as("the stored response is the first one").isEqualTo("500");
    }

    @Test
    @DisplayName("the same key on a different route is a mismatch too")
    void sameKeyOnAnotherRouteConflicts() {
        String key = UUID.randomUUID().toString();

        post(PROBE, SETTLE, keyed(key));
        ResponseEntity<JsonNode> elsewhere = post(PROBE_ALT, SETTLE, keyed(key));

        assertErrorEnvelope(elsewhere, 409, ErrorCode.IDEMPOTENCY_REPLAY.name());
        assertThat(probe.calls()).isEqualTo(1);
    }

    @Test
    @DisplayName("a rejected request stores nothing — the caller may fix the payload and retry")
    void failedCallIsNotStored() {
        String key = UUID.randomUUID().toString();

        ResponseEntity<JsonNode> rejected = post(PROBE_REJECT, SETTLE, keyed(key));

        assertErrorEnvelope(rejected, 409, ErrorCode.SPLIT_MISMATCH.name());
        assertThat(storedKeys()).isZero();

        ResponseEntity<JsonNode> retried = post(PROBE, SETTLE, keyed(key));

        assertThat(retried.getStatusCode().value()).isEqualTo(201);
        assertThat(replayedHeader(retried)).isNull();
        assertThat(probe.calls()).isEqualTo(2);
    }

    // ---- the 48 h window -------------------------------------------------------------------

    @Test
    @DisplayName("a key stops replaying once 48 h have passed on the venue clock")
    void keyExpiresAfter48Hours() {
        String key = UUID.randomUUID().toString();

        ResponseEntity<JsonNode> first = post(PROBE, SETTLE, keyed(key));
        assertThat(first.getBody().get("call").asInt()).isEqualTo(1);

        clock.advance(Duration.ofHours(47));
        ResponseEntity<JsonNode> insideWindow = post(PROBE, SETTLE, keyed(key));
        assertThat(replayedHeader(insideWindow)).isEqualTo("true");
        assertThat(insideWindow.getBody().get("call").asInt()).isEqualTo(1);
        assertThat(probe.calls()).isEqualTo(1);

        clock.advance(Duration.ofHours(2)); // 49 h after the first call
        ResponseEntity<JsonNode> afterWindow = post(PROBE, SETTLE, keyed(key));

        assertThat(afterWindow.getStatusCode().value()).isEqualTo(201);
        assertThat(replayedHeader(afterWindow)).as("the expired key must not replay").isNull();
        assertThat(afterWindow.getBody().get("call").asInt()).isEqualTo(2);
        assertThat(probe.calls()).isEqualTo(2);
        assertThat(storedRow(key).get("call")).isEqualTo("2");
    }

    @Test
    @DisplayName("the reaper deletes rows past the window and keeps the rest")
    void purgeRemovesOnlyExpiredKeys() {
        post(PROBE, SETTLE, keyed(UUID.randomUUID().toString()));
        assertThat(store.purgeExpired()).isZero();
        assertThat(storedKeys()).isEqualTo(1);

        clock.advance(Duration.ofHours(49));

        assertThat(store.purgeExpired()).isEqualTo(1);
        assertThat(storedKeys()).isZero();
    }

    // ---- helpers ---------------------------------------------------------------------------

    /**
     * Bearer plus key. The clock is read fresh every time so a shifted-clock test still gets a
     * token the (equally shifted) JWT verifier accepts.
     */
    private HttpHeaders keyed(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(adminBearer());
        headers.add(IdempotencyFilter.KEY_HEADER, key);
        return headers;
    }

    private static String replayedHeader(ResponseEntity<?> response) {
        return response.getHeaders().getFirst(IdempotencyFilter.REPLAYED_HEADER);
    }

    private int storedKeys() {
        return jdbc.queryForObject("SELECT count(*) FROM idempotency_keys", Integer.class);
    }

    private Map<String, Object> storedRow(String key) {
        return jdbc.queryForMap(
                "SELECT request_hash, status_code, "
                        + "response_body->>'call' AS \"call\", "
                        + "response_body->'echo'->>'amount' AS \"amount\" "
                        + "FROM idempotency_keys WHERE key = CAST(? AS uuid)", key);
    }

    // ---- probe wiring ------------------------------------------------------------------------

    @TestConfiguration
    static class ProbeConfig {

        /** Same clock for auth, for the store and for the test — shifting it shifts everything. */
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }

        @Bean
        @Primary
        IdempotencyPolicy probePolicy() {
            return new IdempotencyPolicy(List.of(
                    new IdempotencyPolicy.Route("POST", PROBE),
                    new IdempotencyPolicy.Route("POST", PROBE_ALT),
                    new IdempotencyPolicy.Route("POST", PROBE_REJECT)));
        }

        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    /** Stands in for {@code POST /payments} until B09 builds the real thing. */
    @RestController
    static class ProbeController {

        private final AtomicInteger calls = new AtomicInteger();

        @PostMapping({"/idempotency-probe", "/idempotency-probe-alt"})
        ResponseEntity<Map<String, Object>> settle(@RequestBody Map<String, Object> body) {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(Map.of("call", calls.incrementAndGet(), "echo", body));
        }

        @PostMapping("/idempotency-probe-reject")
        Map<String, Object> reject(@RequestBody Map<String, Object> body) {
            calls.incrementAndGet();
            throw new ConflictException(ErrorCode.SPLIT_MISMATCH, "Splits do not add up to the bill");
        }

        @PostMapping("/idempotency-open-probe")
        Map<String, Object> open(@RequestBody Map<String, Object> body) {
            return Map.of("call", calls.incrementAndGet());
        }

        int calls() {
            return calls.get();
        }

        void reset() {
            calls.set(0);
        }
    }
}
