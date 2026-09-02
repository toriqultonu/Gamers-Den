package dev.gamersden.common.spi;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The narrow write any package needs into {@code sync_outbox} without reaching for
 * {@code SyncOutboxRepository} (ARCHITECTURE.md §3). Implemented by {@code sync/domain/
 * SyncOutboxService}.
 *
 * <p>{@code MANDATORY} on the implementation, and that is the whole invariant: "money/inventory/
 * tournament/booking mutations insert a {@code sync_outbox} row in the same transaction" (§5.8).
 * An op recorded outside the write it describes could survive a rollback and teach the cloud about
 * a sale the venue never made; an op recorded inside one cannot. Nothing is sent from here — the
 * 30 s pusher drains what committed (docs/backend-architecture.md §9).
 *
 * <p>The aggregate is the table the change belongs to, spelled as §4.1 spells it, and the type is
 * the past-tense fact. Both are constants below rather than free strings so the cloud's vocabulary
 * stays enumerable from the venue's side.
 */
public interface SyncOutboxWriter {

    // ---- aggregates (ARCHITECTURE.md §4.1) ------------------------------------------------------

    String TRANSACTIONS = "transactions";
    String SESSIONS = "sessions";
    String ITEMS = "items";
    String STOCK_MOVEMENTS = "stock_movements";
    String WALLET_LEDGER = "wallet_ledger";
    String BOOKINGS = "bookings";
    String QUEUE_ENTRIES = "queue_entries";
    String TOURNAMENTS = "tournaments";
    String TOURNAMENT_ENTRIES = "tournament_entries";
    String TOURNAMENT_MATCHES = "tournament_matches";
    String SHIFTS = "shifts";
    String EXPENSES = "expenses";

    // ---- op types ---------------------------------------------------------------------------------

    /** A settle: the immutable money row and everything it paid for (§5.3). */
    String SETTLED = "SETTLED";

    /** A Manager+ void — the reversal row, not the original. */
    String VOIDED = "VOIDED";

    /** A negative transaction: a cancel, a no-show, a cancelled event (§5.7). */
    String REFUNDED = "REFUNDED";

    String OPENED = "OPENED";
    String CLOSED = "CLOSED";
    String CREATED = "CREATED";
    String UPDATED = "UPDATED";
    String DELETED = "DELETED";
    String RECORDED = "RECORDED";
    String CANCELLED = "CANCELLED";
    String CHECKED_IN = "CHECKED_IN";
    String SEATED = "SEATED";
    String USED = "USED";
    String ISSUED = "ISSUED";
    String REVOKED = "REVOKED";
    String ENDED = "ENDED";
    String BLOCKS_CHANGED = "BLOCKS_CHANGED";
    String REGISTERED = "REGISTERED";
    String BRACKET_DRAWN = "BRACKET_DRAWN";
    String CONSOLES_BLOCKED = "CONSOLES_BLOCKED";
    String STARTED = "STARTED";
    String EXTENDED = "EXTENDED";
    String WON = "WON";
    String TOPPED_UP = "TOPPED_UP";
    String POINTS_REDEEMED = "POINTS_REDEEMED";

    /**
     * Records one op, in the caller's transaction.
     *
     * @param aggregate the owning table (§4.1)
     * @param type      the past-tense fact
     * @param entityId  the row that moved
     * @param data      what the cloud needs beyond the id; nulls are kept, so a field that is
     *                  genuinely absent stays visible as {@code null} rather than disappearing
     */
    void record(String aggregate, String type, long entityId, Map<String, Object> data);

    /** The same, for a fact the id alone tells in full. */
    default void record(String aggregate, String type, long entityId) {
        record(aggregate, type, entityId, Map.of());
    }

    /**
     * {@code data("shiftId", 4, "memberId", null)} — a small ordered map that accepts nulls, which
     * {@link Map#of} does not. Call sites carry optional ids constantly, and the alternative is a
     * {@code HashMap} and four lines of {@code put} at every one of them.
     */
    static Map<String, Object> data(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("data() takes key/value pairs");
        }
        Map<String, Object> map = new LinkedHashMap<>(keyValues.length / 2);
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }
}
