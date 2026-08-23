package dev.gamersden.tournament.web;

import dev.gamersden.common.events.LiveChange;
import dev.gamersden.common.events.LiveEvent;
import dev.gamersden.common.events.SseHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The SSE {@code tournament-update} event (ARCHITECTURE.md §4.5) — one event in the shape
 * {@code GET /tournaments/{id}} answers with, sent after the transaction that changed it commits.
 *
 * <p>The detail is re-read through {@link TournamentDetailAssembler}, which is what the controller
 * answers the GET with, so the two shapes cannot drift. That also means every countdown in the
 * payload is computed from the server clock at send time (docs/tournaments.md §4) — an extend
 * re-bases the board, the bracket tag and the "Now on" tile off one read, exactly as it does on a
 * fetch.
 */
@Component
public class TournamentLiveEmitter {

    private static final Logger log = LoggerFactory.getLogger(TournamentLiveEmitter.class);

    private final TournamentDetailAssembler details;
    private final SseHub hub;

    public TournamentLiveEmitter(TournamentDetailAssembler details, SseHub hub) {
        this.details = details;
        this.hub = hub;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTournamentChanged(LiveChange.TournamentChanged changed) {
        if (!hub.hasSubscribers()) {
            return;
        }
        try {
            hub.publish(LiveEvent.TOURNAMENT_UPDATE, details.detail(changed.tournamentId()));
        } catch (RuntimeException ex) {
            log.warn("tournament-update for tournament {} not sent: {}",
                    changed.tournamentId(), ex.toString());
        }
    }
}
