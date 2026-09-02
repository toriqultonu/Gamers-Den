package dev.gamersden.sync.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.OffsetDateTime;

/**
 * The one translator between a {@code sync_outbox} row and a {@link SyncOp} on the wire.
 *
 * <p>{@code aggregate} is a column and everything else is the {@code op} JSONB, so a row and an op
 * are the same fact stored two ways. Keeping the knowledge here — rather than in the pusher and
 * again in the receiver — is what makes the round trip exact: what the cloud stores is byte-for-
 * byte what the venue wrote, which is why a re-push is recognisable as a duplicate.
 */
final class SyncOps {

    static final String OP_ID = "opId";
    static final String TYPE = "type";
    static final String ENTITY_ID = "entityId";
    static final String OCCURRED_AT = "occurredAt";
    static final String DATA = "data";

    private SyncOps() {
    }

    /** The op a stored row carries, with the row's own aggregate folded back in. */
    static SyncOp toOp(SyncOutboxEntry entry, ObjectMapper json) {
        JsonNode op = read(entry, json);
        return new SyncOp(op.path(OP_ID).asText(),
                entry.getAggregate(),
                op.path(TYPE).asText(),
                op.path(ENTITY_ID).asLong(),
                OffsetDateTime.parse(op.path(OCCURRED_AT).asText()),
                op.path(DATA));
    }

    /** The {@code op} column for an op arriving from the wire — aggregate stripped back out. */
    static String toColumn(SyncOp op, ObjectMapper json) {
        ObjectNode node = json.createObjectNode();
        node.put(OP_ID, op.opId());
        node.put(TYPE, op.type());
        node.put(ENTITY_ID, op.entityId());
        node.put(OCCURRED_AT, op.occurredAt().toString());
        node.set(DATA, op.data() == null ? json.createObjectNode() : op.data());
        return node.toString();
    }

    private static JsonNode read(SyncOutboxEntry entry, ObjectMapper json) {
        try {
            return json.readTree(entry.getOp());
        } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
            // Only reachable if something wrote the column by hand: the writer always serialises.
            throw new IllegalStateException(
                    "sync_outbox row %d holds unreadable op JSON".formatted(entry.getId()), malformed);
        }
    }
}
