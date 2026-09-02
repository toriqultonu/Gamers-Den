package dev.gamersden.sync.domain;

import java.time.OffsetDateTime;

/**
 * {@code GET /sync/status} — {@code {state, lastSyncedAt, pendingOps}} (api-contract.md, "Live
 * updates &amp; sync"), and the payload of the SSE {@code sync-status} event.
 *
 * @param lastSyncedAt when the newest pushed op left the venue; null until the first push lands
 * @param pendingOps   rows still holding {@code pushed_at IS NULL}
 */
public record SyncStatus(SyncState state, OffsetDateTime lastSyncedAt, long pendingOps) {
}
