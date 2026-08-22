package dev.gamersden.common.spi;

/**
 * The narrow write the {@code billing} package needs from {@code session} — the "mark blocks paid"
 * half of {@code POST /payments} and the un-marking half of {@code POST /payments/{id}/void} —
 * without reaching for {@code SessionBlockRepository} (ARCHITECTURE.md §3).
 *
 * <p>Implemented by {@code session/domain/SessionSettlementService}. Both methods join the
 * caller's transaction: the settle is one DB transaction (invariant §5.3), and a block that has
 * been paid for while the payment itself rolled back would let a customer play for free.
 *
 * <p>The session keeps running. Paying is not ending — the blocks simply stop being billable, and
 * {@code GET /sessions/{id}/bill} charges only what has been bought since (invariant §5.9).
 */
public interface SessionSettlement {

    /**
     * Stamps {@code txId} onto every live block that is not paid for yet.
     *
     * @return what was actually marked, so the caller can prove it matches the bill it charged for
     */
    PaidBlocks markUnpaidBlocksPaid(long sessionId, long txId);

    /**
     * Un-stamps every block a voided transaction paid for, making that time billable again.
     *
     * @return how many blocks went back to unpaid
     */
    int releaseBlocksPaidBy(long txId);

    /**
     * @param blocks how many blocks were stamped
     * @param amount what they came to at their snapshot prices — the gaming figure the receipt
     *               charged for, re-derived from the rows themselves rather than trusted
     */
    record PaidBlocks(int blocks, int amount) {

        public static final PaidBlocks NONE = new PaidBlocks(0, 0);
    }
}
