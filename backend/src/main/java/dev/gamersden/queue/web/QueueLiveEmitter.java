package dev.gamersden.queue.web;

import dev.gamersden.common.events.LiveChange;
import dev.gamersden.common.events.LiveEvent;
import dev.gamersden.common.events.SseHub;
import dev.gamersden.queue.domain.PlayQueueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The SSE {@code queue-update} event (ARCHITECTURE.md §4.5) — the whole rail, in the shape
 * {@code GET /play-queue} answers with, sent after the transaction that changed it commits.
 *
 * <p>The whole rail rather than the one row that moved, because the rail is an <em>order</em>: a
 * token issued goes to the back, a token seated leaves the waiting half for the history strip, and
 * a no-show disappears from it. A single row cannot say where it now sits, and the list is a
 * counter's worth of rows.
 */
@Component
public class QueueLiveEmitter {

    private static final Logger log = LoggerFactory.getLogger(QueueLiveEmitter.class);

    private final PlayQueueService queue;
    private final SseHub hub;

    public QueueLiveEmitter(PlayQueueService queue, SseHub hub) {
        this.queue = queue;
        this.hub = hub;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onQueueChanged(LiveChange.QueueChanged changed) {
        if (!hub.hasSubscribers()) {
            return;
        }
        try {
            hub.publish(LiveEvent.QUEUE_UPDATE,
                    queue.rail().stream().map(QueueEntryView::of).toList());
        } catch (RuntimeException ex) {
            log.warn("queue-update not sent: {}", ex.toString());
        }
    }
}
