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

/**
 * {@code wallet_ledger} — cloned from {@code points_ledger} with {@code LIKE … INCLUDING ALL}, so
 * it shares that table's id sequence (harmless: ids stay unique) and swaps in its own kind CHECK.
 */
@Entity
@Table(name = "wallet_ledger")
public class WalletLedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(nullable = false)
    private int delta;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WalletKind kind;

    @Column(name = "ref_tx_id")
    private Long refTxId;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected WalletLedgerEntry() {
    }

    public WalletLedgerEntry(Long memberId, int delta, WalletKind kind, Long refTxId) {
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

    public WalletKind getKind() {
        return kind;
    }

    public Long getRefTxId() {
        return refTxId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
