package dev.gamersden.shift.domain;

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
 * {@code shifts} — a partial unique index keeps at most one open shift per terminal.
 * {@code expected_cash} and {@code discrepancy} are only written at close, as the immutable
 * Z-report snapshot; while the shift is open they are computed (invariant §5.4).
 */
@Entity
@Table(name = "shifts")
public class Shift {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "staff_id", nullable = false)
    private Long staffId;

    @Column(nullable = false)
    private String terminal = "T1";

    @Column(name = "opening_float", nullable = false)
    private int openingFloat;

    @Generated(event = EventType.INSERT)
    @Column(name = "opened_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime openedAt;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    @Column(name = "counted_cash")
    private Integer countedCash;

    @Column(name = "expected_cash")
    private Integer expectedCash;

    @Column(name = "discrepancy")
    private Integer discrepancy;

    @Column(name = "handover_note")
    private String handoverNote;

    protected Shift() {
    }

    public Shift(Long staffId, String terminal, int openingFloat) {
        this.staffId = staffId;
        this.terminal = terminal;
        this.openingFloat = openingFloat;
    }

    public Long getId() {
        return id;
    }

    public Long getStaffId() {
        return staffId;
    }

    public String getTerminal() {
        return terminal;
    }

    public int getOpeningFloat() {
        return openingFloat;
    }

    public OffsetDateTime getOpenedAt() {
        return openedAt;
    }

    public OffsetDateTime getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(OffsetDateTime closedAt) {
        this.closedAt = closedAt;
    }

    public Integer getCountedCash() {
        return countedCash;
    }

    public void setCountedCash(Integer countedCash) {
        this.countedCash = countedCash;
    }

    public Integer getExpectedCash() {
        return expectedCash;
    }

    public void setExpectedCash(Integer expectedCash) {
        this.expectedCash = expectedCash;
    }

    public Integer getDiscrepancy() {
        return discrepancy;
    }

    public void setDiscrepancy(Integer discrepancy) {
        this.discrepancy = discrepancy;
    }

    public String getHandoverNote() {
        return handoverNote;
    }

    public void setHandoverNote(String handoverNote) {
        this.handoverNote = handoverNote;
    }
}
