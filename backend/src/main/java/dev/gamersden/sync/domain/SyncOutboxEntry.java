package dev.gamersden.sync.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * {@code sync_outbox} — written inside the mutating transaction (invariant §5.8), drained one-way
 * venue → cloud by the 30 s pusher. {@code pushedAt} NULL means still pending.
 */
@Entity
@Table(name = "sync_outbox")
public class SyncOutboxEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String aggregate;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "op", nullable = false)
    private String op;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "pushed_at")
    private OffsetDateTime pushedAt;

    protected SyncOutboxEntry() {
    }

    public SyncOutboxEntry(String aggregate, String op) {
        this.aggregate = aggregate;
        this.op = op;
    }

    public Long getId() {
        return id;
    }

    public String getAggregate() {
        return aggregate;
    }

    public String getOp() {
        return op;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getPushedAt() {
        return pushedAt;
    }

    public void setPushedAt(OffsetDateTime pushedAt) {
        this.pushedAt = pushedAt;
    }
}
