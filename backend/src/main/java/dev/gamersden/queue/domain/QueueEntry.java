package dev.gamersden.queue.domain;

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

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * {@code queue_entries} — one issued daily token (docs/bookings.md §4, §5).
 *
 * <p>The token is queue identity, not payment proof: {@code (token_date, token_no)} is unique, the
 * counter restarts at venue-timezone midnight, and a {@code WAITING} row keeps working across a
 * rollover because the <em>entry id</em> is the key everything else references (invariant §5.10).
 *
 * <p>{@code txId} is {@code NOT NULL} and has no setter, exactly as on a booking: no token without
 * the sale that paid for the time behind it (invariant §5.7).
 */
@Entity
@Table(name = "queue_entries")
public class QueueEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The venue day the counter this token came off belongs to. */
    @Column(name = "token_date", nullable = false)
    private LocalDate tokenDate;

    /** The daily sequence — printed double-height as {@code TOKEN #NN}. */
    @Column(name = "token_no", nullable = false)
    private int tokenNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QueueEntrySource source;

    /** The booking that was checked in, or {@code null} for a walk-up play ticket. */
    @Column(name = "booking_id")
    private Long bookingId;

    @Column(name = "tx_id", nullable = false)
    private Long txId;

    @Column(name = "player_name", nullable = false)
    private String playerName;

    /** {@code PS5|PS4} — enforced against the seat on {@code POST /play-queue/{id}/seat} (B16). */
    @Column(name = "console_type", nullable = false)
    private String consoleType;

    /** 30-minute blocks already paid for; they are born paid when the token is seated. */
    @Column(nullable = false)
    private int blocks;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QueueEntryStatus status = QueueEntryStatus.WAITING;

    /** Set when the token is seated (B16). */
    @Column(name = "session_id")
    private Long sessionId;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected QueueEntry() {
    }

    public QueueEntry(LocalDate tokenDate, int tokenNo, QueueEntrySource source, Long bookingId,
                      Long txId, String playerName, String consoleType, int blocks) {
        this.tokenDate = tokenDate;
        this.tokenNo = tokenNo;
        this.source = source;
        this.bookingId = bookingId;
        this.txId = txId;
        this.playerName = playerName;
        this.consoleType = consoleType;
        this.blocks = blocks;
    }

    public Long getId() {
        return id;
    }

    public LocalDate getTokenDate() {
        return tokenDate;
    }

    public int getTokenNo() {
        return tokenNo;
    }

    public QueueEntrySource getSource() {
        return source;
    }

    public Long getBookingId() {
        return bookingId;
    }

    public Long getTxId() {
        return txId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getConsoleType() {
        return consoleType;
    }

    public int getBlocks() {
        return blocks;
    }

    public QueueEntryStatus getStatus() {
        return status;
    }

    public void setStatus(QueueEntryStatus status) {
        this.status = status;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
