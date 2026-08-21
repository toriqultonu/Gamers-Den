package dev.gamersden.session.repo;

import dev.gamersden.session.domain.Session;
import dev.gamersden.session.domain.SessionState;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
