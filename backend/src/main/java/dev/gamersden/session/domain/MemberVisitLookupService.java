package dev.gamersden.session.domain;

import dev.gamersden.common.config.VenueTime;
import dev.gamersden.common.spi.MemberVisitLookup;
import dev.gamersden.session.repo.SessionBlockRepository;
import dev.gamersden.session.repo.SessionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The {@code session} package's answer to {@link MemberVisitLookup} — the only door {@code member}
 * uses into {@code sessions} (ARCHITECTURE.md §3).
 *
 * <p>A "visit" is a session the member was attached to, live or closed, newest first. The clock
 * figures come out of {@link SessionClock} exactly as they do on the Floor, so a member who is
 * seated right now shows a growing {@code playedSeconds} rather than a stale banked total.
 */
@Service
public class MemberVisitLookupService implements MemberVisitLookup {

    private final SessionRepository sessions;
    private final SessionBlockRepository blocks;
    private final Clock clock;

    public MemberVisitLookupService(SessionRepository sessions, SessionBlockRepository blocks, Clock clock) {
        this.sessions = sessions;
        this.blocks = blocks;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Visit> recentVisits(long memberId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<Session> recent = sessions.findByMemberIdOrderByStartedAtDescIdDesc(
                memberId, PageRequest.of(0, limit));
        if (recent.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> blockCounts = blocks
                .findBySessionIdInAndRemovedFalse(recent.stream().map(Session::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(SessionBlock::getSessionId, Collectors.counting()));
        OffsetDateTime at = VenueTime.now(clock);
        return recent.stream().map(session -> {
            int blockCount = Math.toIntExact(blockCounts.getOrDefault(session.getId(), 0L));
            return new Visit(session.getId(),
                    session.getStationId(),
                    SessionClock.effectiveState(session, blockCount, at).name(),
                    blockCount,
                    SessionClock.consumedSeconds(session, at),
                    session.getStartedAt(),
                    session.getEndedAt());
        }).toList();
    }
}
