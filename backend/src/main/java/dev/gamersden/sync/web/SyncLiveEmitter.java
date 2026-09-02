package dev.gamersden.sync.web;

import dev.gamersden.common.events.LiveChange;
import dev.gamersden.common.events.LiveEvent;
import dev.gamersden.common.events.SseHub;
import dev.gamersden.sync.domain.SyncOutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The SSE {@code sync-status} event (ARCHITECTURE.md §4.5) — the shape {@code GET /sync/status}
 * answers with, so the chip's live update and its 10 s poll can never disagree.
 *
 * <p>Emitted by the pusher, which runs on the scheduler outside any transaction: hence
 * {@code fallbackExecution}, the same reason the printer emitter needs it. The status is read here
 * rather than carried on the event, because by the time this runs the outbox is the truth and a
 * count taken a moment earlier is not.
 */
@Component
public class SyncLiveEmitter {

    private static final Logger log = LoggerFactory.getLogger(SyncLiveEmitter.class);

    private final SyncOutboxService outbox;
    private final SseHub hub;

    public SyncLiveEmitter(SyncOutboxService outbox, SseHub hub) {
        this.outbox = outbox;
        this.hub = hub;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSyncStatusChanged(LiveChange.SyncStatusChanged changed) {
        if (!hub.hasSubscribers()) {
            return;
        }
        try {
            hub.publish(LiveEvent.SYNC_STATUS, SyncStatusView.of(outbox.status()));
        } catch (RuntimeException ex) {
            log.warn("sync-status not sent: {}", ex.toString());
        }
    }
}
