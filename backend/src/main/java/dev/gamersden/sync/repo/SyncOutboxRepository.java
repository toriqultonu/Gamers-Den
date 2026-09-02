package dev.gamersden.sync.repo;

import dev.gamersden.sync.domain.SyncOutboxEntry;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

public interface SyncOutboxRepository extends JpaRepository<SyncOutboxEntry, Long> {

    List<SyncOutboxEntry> findByPushedAtIsNullOrderByIdAsc();

    /**
     * One batch, oldest first. Order is the contract — the cloud replays ops in the sequence the
     * venue committed them, which is what makes a single-writer one-way feed conflict-free
     * (docs/backend-architecture.md §9).
     */
    List<SyncOutboxEntry> findByPushedAtIsNullOrderByIdAsc(Limit limit);

    long countByPushedAtIsNull();

    @Query("select max(e.pushedAt) from SyncOutboxEntry e")
    OffsetDateTime lastPushedAt();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update SyncOutboxEntry e set e.pushedAt = :at where e.id in :ids")
    int markPushed(@Param("ids") Collection<Long> ids, @Param("at") OffsetDateTime at);

    /**
     * Which of these op ids the node already holds — the receiver's dedupe, one query per batch
     * rather than one per op. Native because {@code opId} lives inside the {@code op} JSONB;
     * {@code sync_outbox_op_id_uq} (V006) is the backstop underneath it.
     */
    @Query(value = "SELECT op->>'opId' FROM sync_outbox WHERE op->>'opId' IN (:opIds)",
            nativeQuery = true)
    List<String> opIdsAmong(@Param("opIds") Collection<String> opIds);
}
