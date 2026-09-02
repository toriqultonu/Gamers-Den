package dev.gamersden.sync.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;

/**
 * One op on the wire (docs/backend-architecture.md §9): what the pusher sends and what the cloud
 * receiver stores.
 *
 * <p>{@code opId} is the idempotency key of the whole mechanism — "ordered, idempotent by op id"
 * (api-contract.md, "Live updates &amp; sync"). It is minted when the row is written, inside the
 * mutating transaction, and never changes: a batch re-sent because the venue lost the response
 * carries the same ids and lands as duplicates, not as a second sale.
 *
 * <p>{@code aggregate} lives in its own column on {@code sync_outbox} and the rest lives in
 * {@code op}, so the split here is exactly the split in the table; {@link SyncOps} is the one
 * place that knows it.
 *
 * @param occurredAt venue time at the moment of the write, not of the push — a batch delayed by a
 *                   day of cloud downtime still says when it happened
 */
public record SyncOp(String opId,
                     String aggregate,
                     String type,
                     long entityId,
                     OffsetDateTime occurredAt,
                     JsonNode data) {
}
