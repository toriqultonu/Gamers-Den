package dev.gamersden.common.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, UUID> {

    /**
     * Claims a key for an in-flight request. {@code ON CONFLICT DO NOTHING} makes the claim the
     * atomic arbiter between two simultaneous retries: exactly one row is written, so exactly one
     * request reaches the controller (invariant §5.2 — a retried settle can never double-charge).
     *
     * @return 1 when this caller claimed the key, 0 when it was already taken
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO idempotency_keys (key, request_hash, response_body, status_code)
            VALUES (:key, :hash, 'null'::jsonb, 0)
            ON CONFLICT (key) DO NOTHING
            """, nativeQuery = true)
    int reserve(@Param("key") UUID key, @Param("hash") String hash);

    /** Turns the claim into the stored first response the next retry replays. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE idempotency_keys
               SET response_body = CAST(:body AS jsonb), status_code = :status
             WHERE key = :key
            """, nativeQuery = true)
    int complete(@Param("key") UUID key, @Param("status") int status, @Param("body") String body);

    /** Drops a claim whose request never produced a storable response, so the caller may retry. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM IdempotencyKey k WHERE k.key = :key")
    int deleteKey(@Param("key") UUID key);

    /** Housekeeping for rows past the 48 h window; expiry itself is enforced on read. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM IdempotencyKey k WHERE k.createdAt < :cutoff")
    int deleteCreatedBefore(@Param("cutoff") OffsetDateTime cutoff);
}
