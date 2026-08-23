package dev.gamersden.session.domain;

import dev.gamersden.common.spi.OccupancyLookup;
import dev.gamersden.session.repo.SessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The {@code session} package's answer to {@link OccupancyLookup} — the only door {@code report}
 * uses into {@code sessions} (ARCHITECTURE.md §3).
 *
 * <p>Spans go out unfolded because S9 folds them two ways from one read: per station for the
 * utilisation bars, per hour-of-day for the busiest-hours table. The interval arithmetic that
 * does both is a pure function in {@code report/domain}, testable without a database.
 */
@Service
public class OccupancyLookupService implements OccupancyLookup {

    private final SessionRepository sessions;

    public OccupancyLookupService(SessionRepository sessions) {
        this.sessions = sessions;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SessionSpan> sessionSpans(OffsetDateTime from, OffsetDateTime to) {
        return sessions.overlapping(from, to).stream()
                .map(session -> new SessionSpan(session.getId(), session.getStationId(),
                        session.getStartedAt(), session.getEndedAt()))
                .toList();
    }
}
