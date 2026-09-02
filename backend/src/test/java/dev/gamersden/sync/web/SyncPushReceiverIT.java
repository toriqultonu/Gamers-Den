package dev.gamersden.sync.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamersden.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cloud's half of sync: {@code POST /sync/push}, "ordered, idempotent by op id"
 * (api-contract.md, "Live updates &amp; sync").
 *
 * <p>Two properties, and the venue's ability to survive a bad night rests on both. The first is
 * the door: the caller is a machine with no staff and no shift, so it is authenticated by the
 * shared {@code SYNC_TOKEN} and nothing else gets in. The second is the dedupe: the one failure a
 * pusher cannot distinguish is a batch the cloud stored and whose response went missing, so the
 * venue will offer those ops again — and the mirror has to recognise them rather than book a
 * second copy of the same sale.
 *
 * <p>The receiver exists only where {@code receive-enabled} is on (the {@code cloud} profile),
 * which is why this suite turns it on explicitly: the venue box runs the same JAR and must not
 * expose a door into its own tables.
 */
@TestPropertySource(properties = {
        "gamersden.sync.receive-enabled=true",
        "gamersden.sync.token=" + SyncPushReceiverIT.TOKEN
})
class SyncPushReceiverIT extends AbstractApiIntegrationTest {

    static final String TOKEN = "cloud-shared-sync-secret";

    private static final String PUSH = "/api/v1/sync/push";

    private HttpHeaders authorised;

    @BeforeEach
    void asTheVenueBox() {
        authorised = new HttpHeaders();
        authorised.add("X-Sync-Token", TOKEN);
    }

    @Test
    @DisplayName("a batch lands whole, in order, and is not stored again on a re-push")
    void aBatchLandsOnceHoweverOftenItIsOffered() {
        String first = opId();
        String second = opId();
        List<Map<String, Object>> batch = List.of(
                op(first, "transactions", "SETTLED", 41, Map.of("totalDue", 300)),
                op(second, "bookings", "CREATED", 7, Map.of("blocks", 2)));

        ResponseEntity<JsonNode> accepted = post(PUSH, Map.of("ops", batch), authorised);

        assertThat(accepted.getStatusCode()).as("push refused: %s", accepted.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(accepted.getBody().get("accepted").asInt()).isEqualTo(2);
        assertThat(accepted.getBody().get("duplicates").asInt()).isZero();

        // Stored in the order the venue committed them, and stamped as landed: the mirror owes
        // nobody anything, so its own pusher would find nothing to send.
        assertThat(storedOpIds()).containsExactly(first, second);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sync_outbox WHERE pushed_at IS NULL", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT op ->> 'type' FROM sync_outbox WHERE op ->> 'opId' = ?", String.class,
                first)).isEqualTo("SETTLED");

        // The venue lost the response and offers the same batch again, with a new op behind it.
        String third = opId();
        ResponseEntity<JsonNode> again = post(PUSH, Map.of("ops", List.of(
                batch.get(0), batch.get(1),
                op(third, "shifts", "CLOSED", 3, Map.of("discrepancy", -40)))), authorised);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody().get("accepted").asInt()).isEqualTo(1);
        assertThat(again.getBody().get("duplicates").asInt()).isEqualTo(2);
        assertThat(storedOpIds()).containsExactly(first, second, third);
    }

    @Test
    @DisplayName("a wrong token is 401 and stores nothing")
    void theDoorIsShutWithoutTheSecret() {
        HttpHeaders wrong = new HttpHeaders();
        wrong.add("X-Sync-Token", "not-the-secret");

        assertErrorEnvelope(post(PUSH, Map.of("ops", List.of(
                op(opId(), "transactions", "SETTLED", 1, Map.of()))), wrong), 401, "UNAUTHORIZED");

        assertThat(storedOpIds()).isEmpty();
    }

    @Test
    @DisplayName("no token at all is 401 too — the route is not open, only unauthenticated")
    void noTokenIsNoEntry() {
        assertErrorEnvelope(post(PUSH, Map.of("ops", List.of(
                op(opId(), "transactions", "SETTLED", 1, Map.of()))), null), 401, "UNAUTHORIZED");

        assertThat(storedOpIds()).isEmpty();
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static String opId() {
        return UUID.randomUUID().toString();
    }

    private static Map<String, Object> op(String opId, String aggregate, String type, long entityId,
                                          Map<String, Object> data) {
        return Map.of("opId", opId,
                "aggregate", aggregate,
                "type", type,
                "entityId", entityId,
                "occurredAt", "2026-09-02T18:30:00+06:00",
                "data", data);
    }

    private List<String> storedOpIds() {
        return jdbc.queryForList("SELECT op ->> 'opId' FROM sync_outbox ORDER BY id", String.class);
    }
}
