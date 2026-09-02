package dev.gamersden.sync.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What the cloud answers a push with: how much of the batch was new and how much it already held.
 * The venue does not act on either number — a batch is stamped because the call succeeded, not
 * because of what came back — but the split is what makes a re-push visible in the cloud's log.
 */
@Schema(name = "SyncPushResult")
public record SyncPushResponse(int accepted, int duplicates) {
}
