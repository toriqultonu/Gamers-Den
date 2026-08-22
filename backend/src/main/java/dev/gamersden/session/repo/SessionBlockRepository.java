package dev.gamersden.session.repo;

import dev.gamersden.session.domain.SessionBlock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface SessionBlockRepository extends JpaRepository<SessionBlock, Long> {

    List<SessionBlock> findBySessionIdAndRemovedFalseOrderByIdAsc(Long sessionId);

    List<SessionBlock> findBySessionIdInAndRemovedFalse(Collection<Long> sessionIds);

    /**
     * Every block one transaction paid for — what a void hands back (B10). Removed blocks are
     * included: they were paid for before they were returned, and the pointer has to go too.
     */
    List<SessionBlock> findByPaidTxId(Long paidTxId);
}
