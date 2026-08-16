package dev.gamersden.member.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.OffsetDateTime;

/** {@code points_ledger} — append-only; {@code delta} is signed. */
@Entity
@Table(name = "points_ledger")
public class PointsLedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(nullable = false)
    private int delta;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PointsKind kind;

    @Column(name = "ref_tx_id")
    private Long refTxId;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected PointsLedgerEntry() {
    }

    public PointsLedgerEntry(Long memberId, int delta, PointsKind kind, Long refTxId) {
        this.memberId = memberId;
        this.delta = delta;
        this.kind = kind;
        this.refTxId = refTxId;
    }

    public Long getId() {
        return id;
    }

    public Long getMemberId() {
        return memberId;
    }

    public int getDelta() {
        return delta;
    }

    public PointsKind getKind() {
        return kind;
    }

    public Long getRefTxId() {
        return refTxId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
