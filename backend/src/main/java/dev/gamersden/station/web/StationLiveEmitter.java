package dev.gamersden.station.web;

import dev.gamersden.common.events.LiveChange;
import dev.gamersden.common.events.LiveEvent;
import dev.gamersden.common.events.SseHub;
import dev.gamersden.station.domain.StationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The SSE {@code station-update} event (ARCHITECTURE.md §4.5) — one Floor card, in the shape
 * {@code GET /stations/{id}} answers with, sent after the transaction that moved it commits.
 *
 * <p>The card is re-read here rather than assembled by whoever changed it, and that is what lets
 * one event carry both halves §4.5 asks for: a session's state, blocks and countdown, and the
 * tournament match being played on the same console. {@code SessionService} and
 * {@code MatchExecutionService} each say only "station 3 moved"; the card that comes back is the
 * same one the Floor would fetch, whichever of them spoke.
 *
 * <p>Nothing thrown here may reach the caller. The transaction has already committed — the money
 * is taken, the seat is filled — so a failure to describe it is a stale screen, not a failed sale,
 * and the polling fallback picks the truth up within ten seconds.
 */
@Component
public class StationLiveEmitter {

    private static final Logger log = LoggerFactory.getLogger(StationLiveEmitter.class);

    private final StationService stations;
    private final SseHub hub;

    public StationLiveEmitter(StationService stations, SseHub hub) {
        this.stations = stations;
        this.hub = hub;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onStationChanged(LiveChange.StationChanged changed) {
        if (!hub.hasSubscribers()) {
            return;
        }
        try {
            hub.publish(LiveEvent.STATION_UPDATE,
                    StationView.of(stations.summary(changed.stationId())));
        } catch (RuntimeException ex) {
            log.warn("station-update for station {} not sent: {}", changed.stationId(), ex.toString());
        }
    }
}
