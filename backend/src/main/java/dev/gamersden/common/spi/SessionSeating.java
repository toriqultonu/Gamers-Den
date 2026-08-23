package dev.gamersden.common.spi;

/**
 * The narrow write the {@code queue} package needs from {@code session} —
 * {@code POST /play-queue/{id}/seat} (api-contract.md, "Play queue") — without reaching for
 * {@code SessionRepository} (ARCHITECTURE.md §3).
 *
 * <p>Deliberately thin: seating from the queue rail and seating from {@code POST /sessions} with a
 * {@code queueEntryId} are the same act, so they are the same code. This door only lets the Floor
 * rail start it from the token's side; every guard — 409 {@code STATION_BUSY},
 * {@code STATION_RESERVED}, {@code CONSOLE_TYPE_MISMATCH} — and the whole one-transaction seat of
 * invariant §5.9 stay in {@code SessionService}, where the walk-in path already exercises them.
 */
public interface SessionSeating {

    /** Opens a session on {@code stationId} carrying the token's prepaid blocks, born paid. */
    SeatedSession seat(long stationId, long queueEntryId);

    /**
     * @param blocks         the prepaid blocks loaded onto the seat
     * @param paidBlocks     the subset already carrying a {@code paid_tx_id}; on a fresh seat from
     *                       a token these are the same number, which is what lets the session end
     *                       without a second payment
     * @param netOutstanding what the seat still owes — zero on a seat that has only prepaid time
     */
    record SeatedSession(long sessionId,
                         long stationId,
                         long queueEntryId,
                         String state,
                         int blocks,
                         int paidBlocks,
                         long remainingSeconds,
                         int netOutstanding) {
    }
}
