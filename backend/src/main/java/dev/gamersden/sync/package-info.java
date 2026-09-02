/**
 * Sync outbox, pusher (venue), receiver (cloud). Owns: sync_outbox.
 *
 * <p>The mechanism is one table and three moving parts (ARCHITECTURE.md §5.8,
 * docs/backend-architecture.md §9). Every money, inventory, tournament and booking mutation
 * inserts an op through {@code common.spi.SyncOutboxWriter} <em>inside its own transaction</em>,
 * so the record of a change cannot outlive a rollback or go missing after a commit. A 30 s pusher
 * batches whatever is unpushed to cloud {@code POST /sync/push} and stamps {@code pushed_at} only
 * once the cloud has answered. The receiver stores what it is given, skipping ops it already
 * holds by {@code opId}.
 *
 * <p>Consequently a cloud that is down for a day costs nothing: the venue trades normally, the
 * outbox grows, and the next successful tick drains it in order. One way, single writer, no
 * conflicts — there is no merge to get wrong.
 *
 * <p>Layering (ARCHITECTURE.md §3): {@code web/} (controllers + DTOs) → {@code domain/}
 * (entities, services) → {@code repo/} (Spring Data). No cross-package repository access —
 * call the owning package's service.
 */
package dev.gamersden.sync;
