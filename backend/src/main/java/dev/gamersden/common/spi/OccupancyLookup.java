package dev.gamersden.common.spi;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The narrow read the {@code report} package needs from {@code session} — when each seat was
 * occupied — without reaching for {@code SessionRepository} (ARCHITECTURE.md §3).
 *
 * <p>Spans, not sums, because both S9 tiles that use them slice the same rows differently: station
 * utilisation folds them per station, busiest hours folds them per hour-of-day. Folding twice in
 * SQL would mean two scans of the same window for one screen.
 *
 * <p>Occupancy is wall-clock, not billed time. A seat with a paused clock is still a seat nobody
 * else can take, which is exactly what a utilisation figure is about — {@code consumed_sec} answers
 * the different question of what was played, and it is the bill that cares about that.
 */
public interface OccupancyLookup {

    /**
     * Every session that overlaps the window, oldest first. A live seat comes back with
     * {@code endedAt} null; the caller clips it at "now".
     */
    List<SessionSpan> sessionSpans(OffsetDateTime from, OffsetDateTime to);

    record SessionSpan(long sessionId,
                       long stationId,
                       OffsetDateTime startedAt,
                       OffsetDateTime endedAt) {
    }
}
