package dev.gamersden.session.domain;

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
 * {@code sessions} — one live seat occupancy. A partial unique index allows one non-CLOSED session
 * per station. {@code remainingSeconds} is never stored: it is derived from {@code consumedSec},
 * {@code runningSince} and the paid blocks, server-side (invariant §5.1/§5.4).
 *
 * <p>{@code queueEntryId} is a plain id, not a mapped association — {@code queue_entries} arrives
 * in V003 (B15) and lives in another package (ARCHITECTURE.md §3: no cross-package coupling).
 */
@Entity
@Table(name = "sessions")
public class Session {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "station_id", nullable = false)
    private Long stationId;

    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "shift_id", nullable = false)
    private Long shiftId;

    /** Set when the seat was filled from a token (booking or play ticket). */
    @Column(name = "queue_entry_id")
    private Long queueEntryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SessionState state = SessionState.OPEN;

    @Column(name = "consumed_sec", nullable = false)
    private int consumedSec = 0;

    /** Set iff {@link SessionState#RUNNING}. */
    @Column(name = "running_since")
    private OffsetDateTime runningSince;

    @Generated(event = EventType.INSERT)
    @Column(name = "started_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime startedAt;

    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    protected Session() {
    }

    public Session(Long stationId, Long shiftId) {
        this.stationId = stationId;
        this.shiftId = shiftId;
    }

    public Long getId() {
        return id;
    }

    public Long getStationId() {
        return stationId;
    }

    public Long getMemberId() {
        return memberId;
    }

    public void setMemberId(Long memberId) {
        this.memberId = memberId;
    }

    public Long getShiftId() {
        return shiftId;
    }

    public Long getQueueEntryId() {
        return queueEntryId;
    }

    public void setQueueEntryId(Long queueEntryId) {
        this.queueEntryId = queueEntryId;
    }

    public SessionState getState() {
        return state;
    }

    public void setState(SessionState state) {
        this.state = state;
    }

    public int getConsumedSec() {
        return consumedSec;
    }

    public void setConsumedSec(int consumedSec) {
        this.consumedSec = consumedSec;
    }

    public OffsetDateTime getRunningSince() {
        return runningSince;
    }

    public void setRunningSince(OffsetDateTime runningSince) {
        this.runningSince = runningSince;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public OffsetDateTime getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(OffsetDateTime endedAt) {
        this.endedAt = endedAt;
    }
}
