-- B22: sync. "Ordered, idempotent by op id" (api-contract.md, "Live updates & sync") needs the op
-- id to be unique wherever the row lands. It lives inside the op JSONB rather than in a column of
-- its own, because V001's sync_outbox is a verbatim copy of docs/backend-architecture.md §2 and
-- the MVP is additive-only (ARCHITECTURE.md §4.2) — so the uniqueness is an expression index.
--
-- Both ends of the wire are covered by the one index. On the venue it stops an op being written
-- twice; on the cloud, which stores what it receives into the same table (the migrations are the
-- same set on both, §3), it is the backstop under the receiver's batch dedupe — a re-push after a
-- lost response can never land as a second copy of a sale.
CREATE UNIQUE INDEX sync_outbox_op_id_uq ON sync_outbox ((op ->> 'opId'));
