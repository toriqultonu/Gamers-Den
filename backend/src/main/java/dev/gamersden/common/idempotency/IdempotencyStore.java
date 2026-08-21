package dev.gamersden.common.idempotency;

import dev.gamersden.common.config.VenueTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code idempotency_keys} lifecycle behind {@link IdempotencyFilter}: claim a key, store the
 * first response, replay it for 48 h (api-contract.md §1).
 *
 * <p>A key is claimed <em>before</em> the request runs rather than stored after it, so a
 * double-submit cannot put two settles through the controller — the loser of the insert race is
 * told the request is already in progress instead of charging the customer twice (§5.2).
 */
@Service
public class IdempotencyStore {

    /** Fixed by the contract — not a per-environment knob. */
    public static final Duration TTL = Duration.ofHours(48);

    /**
     * How long a claim may stay unanswered before it is treated as abandoned. Only a process that
     * died between committing its work and storing its response leaves one behind; holding it
     * forever would 409 every retry for two days.
     */
    static final Duration IN_FLIGHT_GRACE = Duration.ofMinutes(5);

    /** {@code status_code} of a claimed-but-unanswered row. */
    static final int IN_FLIGHT = 0;

    /** The JSON null literal — {@code response_body} is {@code NOT NULL} and a 204 has no body. */
    static final String NO_BODY = "null";

    private static final int CLAIM_ATTEMPTS = 2;

    private static final Logger log = LoggerFactory.getLogger(IdempotencyStore.class);

    private final IdempotencyKeyRepository keys;
    private final Clock clock;

    public IdempotencyStore(IdempotencyKeyRepository keys, Clock clock) {
        this.keys = keys;
        this.clock = clock;
    }

    /**
     * Claims {@code key} for a request whose method, path and body hash to {@code hash}, or reports
     * why it cannot be claimed.
     */
    @Transactional
    public Outcome begin(UUID key, String hash) {
        for (int attempt = 0; attempt < CLAIM_ATTEMPTS; attempt++) {
            if (keys.reserve(key, hash) == 1) {
                return Outcome.reserved();
            }
            Optional<IdempotencyKey> found = keys.findById(key);
            if (found.isEmpty()) {
                continue; // purged between the insert and the read — try to claim it again
            }
            IdempotencyKey stored = found.get();
            if (isStale(stored)) {
                keys.deleteKey(key);
                continue;
            }
            if (!stored.getRequestHash().equals(hash)) {
                return Outcome.mismatch();
            }
            if (stored.getStatusCode() == IN_FLIGHT) {
                return Outcome.inFlight();
            }
            return Outcome.replay(stored.getStatusCode(), stored.getResponseBody());
        }
        return Outcome.inFlight();
    }

    /** Stores the first response so every later retry inside the 48 h window replays it. */
    @Transactional
    public void complete(UUID key, int statusCode, String responseBody) {
        keys.complete(key, statusCode, responseBody == null || responseBody.isBlank()
                ? NO_BODY
                : responseBody);
    }

    /** Releases a claim whose request produced nothing worth replaying, so a retry may run. */
    @Transactional
    public void release(UUID key) {
        keys.deleteKey(key);
    }

    /** Housekeeping — expiry is already enforced on read, this only keeps the table small. */
    @Transactional
    public int purgeExpired() {
        int removed = keys.deleteCreatedBefore(VenueTime.now(clock).minus(TTL));
        if (removed > 0) {
            log.info("Purged {} expired idempotency keys", removed);
        }
        return removed;
    }

    private boolean isStale(IdempotencyKey stored) {
        OffsetDateTime now = VenueTime.now(clock);
        Duration window = stored.getStatusCode() == IN_FLIGHT ? IN_FLIGHT_GRACE : TTL;
        boolean stale = stored.getCreatedAt().isBefore(now.minus(window));
        if (stale && stored.getStatusCode() == IN_FLIGHT) {
            log.warn("Idempotency key {} was claimed but never answered — releasing it", stored.getKey());
        }
        return stale;
    }

    /** What {@link #begin} decided, and the stored response when the decision is a replay. */
    public record Outcome(Kind kind, int statusCode, String responseBody) {

        public enum Kind {
            /** The caller owns the key and must run the request. */
            RESERVED,
            /** The first response is stored — send it back with {@code Idempotency-Replayed}. */
            REPLAY,
            /** Same key, different request — 409 {@code IDEMPOTENCY_REPLAY}. */
            MISMATCH,
            /** Another copy of this request is still running — 409 {@code CONFLICT}. */
            IN_FLIGHT
        }

        static Outcome reserved() {
            return new Outcome(Kind.RESERVED, 0, null);
        }

        static Outcome mismatch() {
            return new Outcome(Kind.MISMATCH, 0, null);
        }

        static Outcome inFlight() {
            return new Outcome(Kind.IN_FLIGHT, 0, null);
        }

        static Outcome replay(int statusCode, String responseBody) {
            return new Outcome(Kind.REPLAY, statusCode, responseBody);
        }
    }
}
