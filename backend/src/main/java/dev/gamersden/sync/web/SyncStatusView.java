package dev.gamersden.sync.web;

import dev.gamersden.sync.domain.SyncStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * {@code GET /sync/status} → {@code {state, lastSyncedAt, pendingOps}} (api-contract.md, "Live
 * updates &amp; sync"), and the payload of the SSE {@code sync-status} event — the same shape, as
 * §4.5 requires.
 *
 * <p>{@code state} is what the persistent chip renders: {@code SYNCED}, {@code SYNCING},
 * {@code OFFLINE} (design.md §4, "synced / syncing / offline since HH:MM"). The "since" the chip
 * shows is {@code lastSyncedAt}, which is absent until something has actually been pushed.
 */
@Schema(name = "SyncStatus", description = "Where the venue's outbox stands against the cloud")
public record SyncStatusView(String state, OffsetDateTime lastSyncedAt, long pendingOps) {

    public static SyncStatusView of(SyncStatus status) {
        return new SyncStatusView(status.state().name(), status.lastSyncedAt(), status.pendingOps());
    }
}
