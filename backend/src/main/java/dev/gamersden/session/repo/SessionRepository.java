package dev.gamersden.session.repo;

import dev.gamersden.session.domain.Session;
import dev.gamersden.session.domain.SessionState;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SessionRepository extends JpaRepository<Session, Long> {

    /** At most one row can match — {@code one_live_session_per_station} (V001) sees to that. */
    Optional<Session> findByStationIdAndStateNot(Long stationId, SessionState state);

    List<Session> findByStateNot(SessionState state);

    List<Session> findByStateIn(Collection<SessionState> states);

    List<Session> findByStateOrderByEndedAtDesc(SessionState state);

    boolean existsByStationId(Long stationId);

    /** The member detail's recent-visits strip (B08), newest first — see {@code MemberVisitLookup}. */
    List<Session> findByMemberIdOrderByStartedAtDescIdDesc(Long memberId, Pageable pageable);

    /**
     * Every session whose seat was occupied at some point inside the window, oldest first — the
     * rows behind S9's station utilisation and busiest hours (see {@code OccupancyLookup}).
     *
     * <p>A live session has no {@code endedAt} and so overlaps everything after it started; the
     * caller clips it at "now". Wall-clock occupancy is the question here, not billed time: a
     * paused seat is still a seat nobody else can have.
     */
    @Query("""
            SELECT s FROM Session s
             WHERE s.startedAt < :to
               AND (s.endedAt IS NULL OR s.endedAt > :from)
             ORDER BY s.startedAt ASC, s.id ASC
            """)
    List<Session> overlapping(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);
}
