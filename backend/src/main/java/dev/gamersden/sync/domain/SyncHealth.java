package dev.gamersden.sync.domain;

import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * Whether the cloud answered last time anyone asked it to.
 *
 * <p>Deliberately in memory and not a column. It is a fact about the network right now, not about
 * the venue's data: everything durable — which ops are outstanding, when the last one landed —
 * lives in {@code sync_outbox}, so a restart forgets only the thing it should forget, and the
 * first tick after it re-establishes the truth. Reading it costs nothing, which matters because
 * {@code GET /sync/status} is on the shell of every screen.
 */
@Component
public class SyncHealth {

    private volatile OffsetDateTime lastAttemptAt;
    private volatile String lastError;

    /** The cloud took a batch (or there was nothing to take and the endpoint was reachable). */
    public void succeeded(OffsetDateTime at) {
        this.lastAttemptAt = at;
        this.lastError = null;
    }

    public void failed(OffsetDateTime at, String reason) {
        this.lastAttemptAt = at;
        this.lastError = reason == null || reason.isBlank() ? "unreachable" : reason;
    }

    /** False before the first attempt: an untried cloud is not a failed one. */
    public boolean lastAttemptFailed() {
        return lastError != null;
    }

    public String lastError() {
        return lastError;
    }

    public OffsetDateTime lastAttemptAt() {
        return lastAttemptAt;
    }
}
