package dev.gamersden.catalog.domain;

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

/** {@code stock_movements} — append-only stock audit; {@code delta} is signed. */
@Entity
@Table(name = "stock_movements")
public class StockMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(nullable = false)
    private int delta;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StockMovementReason reason;

    @Column(name = "ref_tx_id")
    private Long refTxId;

    @Column(name = "staff_id", nullable = false)
    private Long staffId;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected StockMovement() {
    }

    public StockMovement(Long itemId, int delta, StockMovementReason reason, Long refTxId, Long staffId) {
        this.itemId = itemId;
        this.delta = delta;
        this.reason = reason;
        this.refTxId = refTxId;
        this.staffId = staffId;
    }

    public Long getId() {
        return id;
    }

    public Long getItemId() {
        return itemId;
    }

    public int getDelta() {
        return delta;
    }

    public StockMovementReason getReason() {
        return reason;
    }

    public Long getRefTxId() {
        return refTxId;
    }

    public Long getStaffId() {
        return staffId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
