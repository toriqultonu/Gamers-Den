package dev.gamersden.session.domain;

import dev.gamersden.common.config.VenueTime;
import dev.gamersden.common.spi.SessionLookup;
import dev.gamersden.session.repo.SessionBlockRepository;
import dev.gamersden.session.repo.SessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The {@code session} package's answer to {@link SessionLookup} — the only door {@code station}
 * uses into {@code sessions} / {@code session_blocks} (ARCHITECTURE.md, package layout: call the
 * owning package's service, never a foreign repository).
 *
 * <p>Read-only and derived: the state, the countdown and the paid-block count all come out of
 * {@link SessionClock} at read time, the same arithmetic {@code SessionService} writes with. A
 * card therefore shows LOCKED the instant a countdown reaches zero, without waiting for the
 * expiry sweeper to persist the flip (invariants: server-side time, derived values are never
 * stored).
 */
@Service
public class SessionLookupService implements SessionLookup {

    private final SessionRepository sessions;
    private final SessionBlockRepository blocks;
    private final Clock clock;

    public SessionLookupService(SessionRepository sessions, SessionBlockRepository blocks, Clock clock) {
        this.sessions = sessions;
        this.blocks = blocks;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, LiveSession> liveSessionsByStation() {
        List<Session> live = sessions.findByStateNot(SessionState.CLOSED);
        if (live.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<SessionBlock>> bySession = blocksOf(live.stream().map(Session::getId).toList());
        OffsetDateTime at = VenueTime.now(clock);
        Map<Long, LiveSession> summaries = new HashMap<>();
        for (Session session : live) {
            summaries.put(session.getStationId(),
                    summarise(session, bySession.getOrDefault(session.getId(), List.of()), at));
        }
        return Map.copyOf(summaries);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LiveSession> liveSessionOn(long stationId) {
        return sessions.findByStationIdAndStateNot(stationId, SessionState.CLOSED)
                .map(session -> summarise(session,
                        blocks.findBySessionIdAndRemovedFalseOrderByIdAsc(session.getId()),
                        VenueTime.now(clock)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LiveSession> liveSession(long sessionId) {
        return sessions.findById(sessionId)
                .filter(session -> session.getState().isLive())
                .map(session -> summarise(session,
                        blocks.findBySessionIdAndRemovedFalseOrderByIdAsc(session.getId()),
                        VenueTime.now(clock)));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasSessionHistory(long stationId) {
        return sessions.existsByStationId(stationId);
    }

    private Map<Long, List<SessionBlock>> blocksOf(List<Long> sessionIds) {
        return blocks.findBySessionIdInAndRemovedFalse(sessionIds).stream()
                .collect(Collectors.groupingBy(SessionBlock::getSessionId));
    }

    private static LiveSession summarise(Session session, List<SessionBlock> live, OffsetDateTime at) {
        int paid = (int) live.stream().filter(block -> block.getPaidTxId() != null).count();
        return new LiveSession(session.getId(),
                SessionClock.effectiveState(session, live.size(), at).name(),
                session.getMemberId(), live.size(), paid,
                SessionClock.remainingSeconds(session, live.size(), at), session.getStartedAt());
    }
}
