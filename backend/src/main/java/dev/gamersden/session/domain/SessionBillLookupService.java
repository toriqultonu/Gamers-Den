package dev.gamersden.session.domain;

import dev.gamersden.common.config.VenueTime;
import dev.gamersden.common.spi.SessionBillLookup;
import dev.gamersden.session.repo.SessionBlockRepository;
import dev.gamersden.session.repo.SessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;

/**
 * The {@code session} package's answer to {@link SessionBillLookup} — the only door
 * {@code billing} uses into {@code sessions} / {@code session_blocks} (ARCHITECTURE.md §3).
 *
 * <p>Read-only, and it hands over blocks rather than sums: which of them are still billable is the
 * bill's arithmetic, not the session's. Closed sessions answer too — a bill stays readable after
 * the seat is emptied, it simply has nothing left to charge for.
 */
@Service
public class SessionBillLookupService implements SessionBillLookup {

    private final SessionRepository sessions;
    private final SessionBlockRepository blocks;
    private final Clock clock;

    public SessionBillLookupService(SessionRepository sessions, SessionBlockRepository blocks, Clock clock) {
        this.sessions = sessions;
        this.blocks = blocks;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BillableSession> findForBill(long sessionId) {
        return sessions.findById(sessionId).map(session -> new BillableSession(
                session.getId(),
                session.getStationId(),
                session.getMemberId(),
                session.getState().name(),
                blocks.findBySessionIdAndRemovedFalseOrderByIdAsc(session.getId()).stream()
                        .map(block -> new TimeBlock(block.getPrice(), block.getPaidTxId() != null))
                        .toList(),
                VenueTime.now(clock)));
    }
}
