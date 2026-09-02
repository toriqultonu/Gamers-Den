package dev.gamersden.sync.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gamersden.common.config.VenueTime;
import dev.gamersden.common.spi.SyncOutboxWriter;
import dev.gamersden.sync.repo.SyncOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The transactional outbox (ARCHITECTURE.md §5.8, docs/backend-architecture.md §9).
 *
 * <p>{@link #record} is {@link Propagation#MANDATORY} on purpose and it is the load-bearing part
 * of this class: an op is written by the very transaction that made the change, so the outbox can
 * never describe a sale that rolled back, and a sale can never commit without its op. There is no
 * queue in memory, no listener, and nothing to lose on a restart — a crash between the commit and
 * the push costs nothing, because the push is just a row that has not been marked yet.
 *
 * <p>Everything else here is the drain side: the pusher asks for a batch, sends it, and comes back
 * to stamp {@code pushed_at}. Marking after the response rather than before is why a cloud that
 * dies mid-batch loses nothing — the same ops are offered again, carrying the same {@code opId},
 * and the receiver recognises them.
 */
@Service
public class SyncOutboxService implements SyncOutboxWriter {

    private static final Logger log = LoggerFactory.getLogger(SyncOutboxService.class);

    private final SyncOutboxRepository outbox;
    private final SyncHealth health;
    private final ObjectMapper json;
    private final Clock clock;

    public SyncOutboxService(SyncOutboxRepository outbox, SyncHealth health, ObjectMapper json,
                             Clock clock) {
        this.outbox = outbox;
        this.health = health;
        this.json = json;
        this.clock = clock;
    }

    // ---- the write --------------------------------------------------------------------------

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String aggregate, String type, long entityId, Map<String, Object> data) {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put(SyncOps.OP_ID, UUID.randomUUID().toString());
        op.put(SyncOps.TYPE, type);
        op.put(SyncOps.ENTITY_ID, entityId);
        op.put(SyncOps.OCCURRED_AT, VenueTime.now(clock).toString());
        op.put(SyncOps.DATA, data == null ? Map.of() : data);
        outbox.save(new SyncOutboxEntry(aggregate, serialise(op)));
        log.debug("outbox <- {} {} {}", aggregate, type, entityId);
    }

    // ---- the drain --------------------------------------------------------------------------

    /** The next batch to send, oldest first, with the row ids the push will stamp. */
    @Transactional(readOnly = true)
    public Batch pending(int batchSize) {
        List<SyncOutboxEntry> rows = outbox.findByPushedAtIsNullOrderByIdAsc(Limit.of(batchSize));
        return new Batch(rows.stream().map(SyncOutboxEntry::getId).toList(),
                rows.stream().map(entry -> SyncOps.toOp(entry, json)).toList());
    }

    /**
     * One batch: the ops to send and the rows behind them, in the same order.
     *
     * @param ids what {@link #markPushed} stamps once the cloud has answered — never before
     */
    public record Batch(List<Long> ids, List<SyncOp> ops) {

        public boolean isEmpty() {
            return ops.isEmpty();
        }

        public int size() {
            return ops.size();
        }
    }

    @Transactional
    public void markPushed(Collection<Long> ids) {
        if (!ids.isEmpty()) {
            outbox.markPushed(ids, VenueTime.now(clock));
        }
    }

    // ---- GET /sync/status ---------------------------------------------------------------------

    /**
     * The chip's three states (design.md §4). OFFLINE is the last <em>attempt</em>, not the last
     * op: a venue with an empty outbox and a cloud that just refused the connection is offline,
     * and saying "synced" there would be the one lie the chip exists to prevent.
     */
    @Transactional(readOnly = true)
    public SyncStatus status() {
        long pending = outbox.countByPushedAtIsNull();
        SyncState state = health.lastAttemptFailed()
                ? SyncState.OFFLINE
                : (pending > 0 ? SyncState.SYNCING : SyncState.SYNCED);
        return new SyncStatus(state, outbox.lastPushedAt(), pending);
    }

    // ---- the cloud side -----------------------------------------------------------------------

    /**
     * Stores ops arriving at {@code POST /sync/push}, skipping any this node already holds.
     *
     * <p>The landing table is {@code sync_outbox} itself: the migrations are the same set on venue
     * and cloud (docs/backend-architecture.md §3), the sync package owns exactly one table (§4.1),
     * and an op is an op whichever end of the wire it is on. Received rows are stamped
     * {@code pushed_at} as they land, so they are never mistaken for work this node owes anybody —
     * the feed is one-way.
     *
     * @return how many of the batch were new
     */
    @Transactional
    public int receive(List<SyncOp> ops) {
        if (ops.isEmpty()) {
            return 0;
        }
        List<String> known = outbox.opIdsAmong(ops.stream().map(SyncOp::opId).toList());
        OffsetDateTime at = VenueTime.now(clock);
        int stored = 0;
        for (SyncOp op : ops) {
            if (known.contains(op.opId())) {
                continue;
            }
            SyncOutboxEntry landed = new SyncOutboxEntry(op.aggregate(), SyncOps.toColumn(op, json));
            landed.setPushedAt(at);
            outbox.save(landed);
            stored++;
        }
        log.info("sync push received: {} op(s), {} new, {} already held", ops.size(), stored,
                ops.size() - stored);
        return stored;
    }

    private String serialise(Map<String, Object> op) {
        try {
            return json.writeValueAsString(op);
        } catch (JsonProcessingException notSerialisable) {
            // A caller handed us something Jackson cannot write. Failing the money transaction is
            // correct: §5.8 says the op ships with the change, so no op means no change.
            throw new IllegalArgumentException("sync op payload is not serialisable", notSerialisable);
        }
    }
}
