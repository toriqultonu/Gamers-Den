package dev.gamersden.alert.web;

import dev.gamersden.alert.domain.AlertService;
import dev.gamersden.common.events.LiveChange;
import dev.gamersden.common.events.LiveEvent;
import dev.gamersden.common.events.SseHub;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The SSE {@code alert} event (ARCHITECTURE.md §4.5) — one row of {@code GET /alerts}, sent once
 * the transaction that raised it has committed.
 *
 * <p>After commit is the whole contract here: a discrepancy alert is written inside the shift
 * close, and a close that rolls back must leave no card on anybody's screen claiming the drawer
 * was short.
 *
 * <p>The read that builds the payload runs in {@link AlertService}'s own read-only transaction:
 * by the time a listener runs the original one is over, so the committed row is re-read rather
 * than a snapshot from inside it being trusted.
 */
@Component
public class AlertLiveEmitter {

    private final AlertService alerts;
    private final SseHub hub;

    public AlertLiveEmitter(AlertService alerts, SseHub hub) {
        this.alerts = alerts;
        this.hub = hub;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAlertRaised(LiveChange.AlertRaised raised) {
        if (!hub.hasSubscribers()) {
            return;
        }
        hub.publish(LiveEvent.ALERT, AlertView.of(alerts.get(raised.alertId())));
    }
}
