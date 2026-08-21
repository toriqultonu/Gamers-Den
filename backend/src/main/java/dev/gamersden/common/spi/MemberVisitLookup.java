package dev.gamersden.common.spi;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The narrow read the {@code member} package needs from {@code session} — the "recent visits" strip
 * on the member detail (api-contract.md, {@code GET /members/{id}}) — without reaching for
 * {@code SessionRepository} (ARCHITECTURE.md §3: no cross-package repository access, call the
 * owning package's service).
 *
 * <p>Implemented by {@code session/domain/MemberVisitLookupService}. Read-only, and
 * {@code playedSeconds} is derived at read time from {@code consumed_sec} / {@code running_since}
 * like every other duration in the system (invariants §5.1, §5.4).
 */
public interface MemberVisitLookup {

    /** The member's most recent seats, newest first, at most {@code limit} of them. */
    List<Visit> recentVisits(long memberId, int limit);

    /**
     * One row of the visits strip.
     *
     * @param state         the effective {@code sessions.state} — a live seat still reads live here
     * @param blocks        non-removed 30-minute blocks the visit bought
     * @param playedSeconds banked time plus, on a running seat, the current stretch
     * @param endedAt       {@code null} while the visit is still on the floor
     */
    record Visit(long sessionId,
                 long stationId,
                 String state,
                 int blocks,
                 long playedSeconds,
                 OffsetDateTime startedAt,
                 OffsetDateTime endedAt) {
    }
}
