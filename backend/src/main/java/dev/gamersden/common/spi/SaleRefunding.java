package dev.gamersden.common.spi;

/**
 * The narrow write a feature package needs from {@code billing} when it has to hand money back
 * for part of a sale — a cancelled tournament's entries (docs/tournaments.md §3) and, from B15, a
 * booking cancelled inside its cutoff — without reaching for {@code TransactionRepository}
 * (ARCHITECTURE.md §3).
 *
 * <p>This is not a void. A void reverses a whole sale and is Manager+, same-shift, and refuses
 * once the sale has been voided already; a refund here undoes <em>one bucket</em> of a sale that
 * otherwise stands — the customer keeps the time and the food they bought with the same receipt.
 * What both share is invariant §5.7: the money goes back out as its own negative transaction,
 * posted to the shift doing the refunding, never as an edit to the original row.
 *
 * <p>Implemented by {@code billing/domain/RefundService} with
 * {@link org.springframework.transaction.annotation.Propagation#MANDATORY} — a refund is part of
 * whatever decision caused it, so a cancel that rolls back cannot leave money handed out.
 */
public interface SaleRefunding {

    /** Writes one negative transaction against {@code originalTxId}. */
    Refund refund(RefundRequest request);

    /**
     * @param originalTxId the sale being partly undone — its tender methods are what the refund is
     *                     paid back through
     * @param amount       a positive magnitude; the direction belongs to the method, not the number
     * @param bucket       which of the four snapshot columns the refund is taken out of
     * @param reason       logged, and carried onto the refund for the audit trail
     */
    record RefundRequest(long originalTxId, int amount, Bucket bucket, String reason) {
    }

    /** The transaction snapshot's four takings columns (invariant §5.7). */
    enum Bucket {
        GAMING,
        FNB,
        TOURNAMENT,
        BOOKING
    }

    /** @param amount the negative {@code total_due} that was written */
    record Refund(long transactionId, String publicId, int amount) {
    }
}
