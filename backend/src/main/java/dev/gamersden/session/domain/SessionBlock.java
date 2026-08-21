package dev.gamersden.session.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.OffsetDateTime;

/**
 * {@code session_blocks} — one row per 30-minute block, priced by snapshot at purchase so later
 * rate edits never rewrite history. {@code paidTxId} is NULL until settled; prepaid blocks from a
 * booking or play ticket are born paid, carrying the sale transaction (invariant §5.9).
 */
@Entity
@Table(name = "session_blocks")
public class SessionBlock {

    /** Every block is 30 minutes of play (docs/backend-architecture.md, section 2). */
    public static final int MINUTES = 30;

    public static final long SECONDS = MINUTES * 60L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(nullable = false)
    private int price;

    @Column(name = "paid_tx_id")
    private Long paidTxId;

    @Column(nullable = false)
    private boolean removed = false;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected SessionBlock() {
    }

    public SessionBlock(Long sessionId, int price, Long paidTxId) {
        this.sessionId = sessionId;
        this.price = price;
        this.paidTxId = paidTxId;
    }

    public Long getId() {
        return id;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public int getPrice() {
        return price;
    }

    public Long getPaidTxId() {
        return paidTxId;
    }

    public void setPaidTxId(Long paidTxId) {
        this.paidTxId = paidTxId;
    }

    public boolean isRemoved() {
        return removed;
    }

    public void setRemoved(boolean removed) {
        this.removed = removed;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
