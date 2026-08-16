package dev.gamersden.session.repo;

import dev.gamersden.session.domain.Session;
import dev.gamersden.session.domain.SessionState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SessionRepository extends JpaRepository<Session, Long> {

    Optional<Session> findByStationIdAndStateNot(Long stationId, SessionState state);

    List<Session> findByStateNot(SessionState state);
}
