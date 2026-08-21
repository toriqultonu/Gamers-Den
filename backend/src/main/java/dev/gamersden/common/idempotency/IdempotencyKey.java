package dev.gamersden.common.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * {@code idempotency_keys} — the stored first response for a guarded POST (invariant §5.2).
 * An identical retry replays {@code responseBody}; the same key with a different
 * {@code requestHash} is a 409. Rows live 48 h.
 *
 * <p>{@link #getStatusCode()} {@code == 0} is not an HTTP status: it marks a key
 * {@link IdempotencyStore#begin claimed} by a request that has not answered yet, which is how two
 * simultaneous retries are kept from both reaching the controller.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey {

    @Id
    @Column(name = "key", nullable = false)
    private UUID key;

    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body", nullable = false)
    private String responseBody;

    @Column(name = "status_code", nullable = false)
    private int statusCode;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected IdempotencyKey() {
    }

    public IdempotencyKey(UUID key, String requestHash, String responseBody, int statusCode) {
        this.key = key;
        this.requestHash = requestHash;
        this.responseBody = responseBody;
        this.statusCode = statusCode;
    }

    public UUID getKey() {
        return key;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
