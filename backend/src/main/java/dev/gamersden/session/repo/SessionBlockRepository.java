package dev.gamersden.session.repo;

import dev.gamersden.session.domain.SessionBlock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SessionBlockRepository extends JpaRepository<SessionBlock, Long> {

    List<SessionBlock> findBySessionIdAndRemovedFalseOrderByIdAsc(Long sessionId);
}
