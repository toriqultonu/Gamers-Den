package dev.gamersden.queue.domain;

import dev.gamersden.common.config.VenueTime;
import dev.gamersden.common.events.LiveEvents;
import dev.gamersden.common.spi.QueueTokenIssuing;
import dev.gamersden.common.spi.QueueTokenLookup;
import dev.gamersden.queue.repo.QueueEntryRepository;
import dev.gamersden.queue.repo.TokenSeqRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The {@code queue} package's answer to {@link QueueTokenIssuing} — the one place a daily token
 * comes from (ARCHITECTURE.md §3, invariant §5.10).
 *
 * <p>Three things make the counter behave:
 *
 * <ul>
 *   <li><strong>One counter, two counters selling.</strong> Bookings checking in and walk-up play
 *       tickets take numbers from the same {@code token_seq} row, so the queue rail reads as a
 *       single ordered list rather than two interleaved sequences.</li>
 *   <li><strong>The day is the key.</strong> {@code token_seq} is keyed by the venue day, so the
 *       counter restarts at Asia/Dhaka midnight with no scheduled job to run and nothing to reset.
 *       A {@code WAITING} token issued yesterday keeps working, because everything downstream
 *       references the {@code queue_entries} id, not the number on the paper.</li>
 *   <li><strong>Allocation is a row-locked upsert.</strong> {@link TokenSeqRepository#allocate}
 *       is one statement that takes the row's write lock and holds it to commit, so two terminals
 *       checking in at the same instant take consecutive numbers instead of colliding at
 *       {@code UNIQUE (token_date, token_no)}.</li>
 * </ul>
 *
 * <p>{@link Propagation#MANDATORY}: a token is part of whatever decision issued it. A check-in
 * that rolls back must not burn a number, and a queue entry must never exist without the
 * transaction that paid for the time behind it.
 */
@Service
public class QueueTokenService implements QueueTokenIssuing, QueueTokenLookup {

    private static final Logger log = LoggerFactory.getLogger(QueueTokenService.class);

    private final QueueEntryRepository entries;
    private final TokenSeqRepository tokens;
    private final LiveEvents live;
    private final Clock clock;

    public QueueTokenService(QueueEntryRepository entries, TokenSeqRepository tokens,
                             LiveEvents live, Clock clock) {
        this.entries = entries;
        this.tokens = tokens;
        this.live = live;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public IssuedToken issue(TokenRequest request) {
        LocalDate day = VenueTime.businessDay(clock);
        int tokenNo = tokens.allocate(day);
        QueueEntry entry = entries.saveAndFlush(new QueueEntry(day, tokenNo,
                QueueEntrySource.valueOf(request.source()), request.bookingId(), request.txId(),
                request.playerName(), request.consoleType(), request.blocks(), request.playAmount()));
        log.info("queue entry {} issued TOKEN #{} of {} from {} for \"{}\" ({} x 30 min on {} at "
                        + "{} BDT) against transaction {}", entry.getId(), tokenNo, day,
                request.source(), request.playerName(), request.blocks(), request.consoleType(),
                request.playAmount(), request.txId());
        // One announcement covers both counters: a walk-up sale and a booking check-in land here,
        // so the rail is re-sent from one place after the paying transaction commits (§4.5).
        live.queueChanged();
        return new IssuedToken(entry.getId(), tokenNo, day);
    }

    // ---- reads --------------------------------------------------------------------------------

    /**
     * The queue rail's "who plays next" — everyone still waiting, oldest counter first.
     *
     * <p>Not filtered to today on purpose (docs/bookings.md §7): a token that went unseated over a
     * rollover is still a customer who has paid, so it keeps its place at the head of the rail and
     * carries its issue date for the operator to see.
     */
    @Transactional(readOnly = true)
    public List<QueueEntry> waiting() {
        return entries.findByStatusOrderByTokenDateAscTokenNoAsc(QueueEntryStatus.WAITING);
    }

    /** Today's seated tokens — the rail's history strip, in the order the counter issued them. */
    @Transactional(readOnly = true)
    public List<QueueEntry> seatedToday() {
        return entries.findByTokenDateAndStatusOrderByTokenNoAsc(VenueTime.businessDay(clock),
                QueueEntryStatus.SEATED);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, Token> tokensOf(Collection<Long> queueEntryIds) {
        if (queueEntryIds == null || queueEntryIds.isEmpty()) {
            return Map.of();
        }
        return entries.findAllById(queueEntryIds).stream()
                .collect(Collectors.toMap(QueueEntry::getId, QueueTokenService::token,
                        (first, second) -> first));
    }

    private static Token token(QueueEntry entry) {
        return new Token(entry.getId(), entry.getTokenNo(), entry.getTokenDate(),
                entry.getStatus().name());
    }
}
