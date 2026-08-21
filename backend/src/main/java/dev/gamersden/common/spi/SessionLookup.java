package dev.gamersden.common.spi;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * The narrow read the {@code station} package needs from {@code session} — the live-session
 * summary on a station card and the {@code STATION_IN_USE} delete guard — without reaching for
 * {@code SessionRepository} (ARCHITECTURE.md §3: no cross-package repository access, call the
 * owning package's service).
 *
 * <p>Implemented by {@code session/domain/SessionLookupService}. The session state machine,
 * blocks and clock land in B06; everything published here is read-only and derived.
 */
public interface SessionLookup {

    /** Live (non-CLOSED) sessions keyed by station id — at most one per station (§5 DDL index). */
    Map<Long, LiveSession> liveSessionsByStation();

    /** The live session occupying a station, or empty when the seat is free. */
    Optional<LiveSession> liveSessionOn(long stationId);

    /** True when any session row — live or closed — still points at the station. */
    boolean hasSessionHistory(long stationId);

    /**
     * A station card's session line. {@code remainingSeconds} is computed server-side from
     * {@code consumed_sec} / {@code running_since} and never stored (invariants §5.1, §5.4).
     *
     * @param state the {@code sessions.state} value — {@code OPEN|RUNNING|PAUSED|LOCKED}
     * @param blocks non-removed 30-minute blocks bought so far
     * @param paidBlocks the subset already carrying a {@code paid_tx_id} (prepaid or settled)
     */
    record LiveSession(long id,
                       String state,
                       Long memberId,
                       int blocks,
                       int paidBlocks,
                       long remainingSeconds,
                       OffsetDateTime startedAt) {
    }
}
