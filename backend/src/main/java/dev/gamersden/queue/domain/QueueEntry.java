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

    /**
     * {@code blocks ×} the console's block rate at the moment of sale (V004). The snapshot the
     * prepaid {@code session_blocks} are born at, and the amount a no-show refund hands back — a
     * later {@code PUT /pricing} can reach neither (invariants §5.9, §5.11).
     */
    @Column(name = "play_amount", nullable = false)
    private int playAmount;

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
                      Long txId, String playerName, String consoleType, int blocks, int playAmount) {
        this.tokenDate = tokenDate;
        this.tokenNo = tokenNo;
        this.source = source;
        this.bookingId = bookingId;
        this.txId = txId;
        this.playerName = playerName;
        this.consoleType = consoleType;
        this.blocks = blocks;
        this.playAmount = playAmount;
    }

    // ---- derived (never stored — invariant §5.4) ----------------------------------------------

    /**
     * What one prepaid block cost. Exact by construction: {@code playAmount} is only ever written
     * as {@code blocks ×} a block rate, so the division never loses a taka.
     */
    public int blockPrice() {
        return blocks == 0 ? 0 : playAmount / blocks;
    }

    /** True while this token can still be seated or refunded. */
    public boolean isWaiting() {
        return status == QueueEntryStatus.WAITING;
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

    public int getPlayAmount() {
        return playAmount;
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
