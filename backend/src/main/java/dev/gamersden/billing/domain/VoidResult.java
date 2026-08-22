package dev.gamersden.billing.domain;

/**
 * What {@code POST /payments/{id}/void} hands back. The contract fixes the request
 * ({@code {reason}}, Manager+, same-shift) but not the response; what a void produces is a
 * <em>new</em> transaction, so that is what is returned — the reversal's own id and public id,
 * next to the sale it cancelled.
 *
 * <p>Refunds are negative transactions, never edits to the original (invariant §5.7): the sale row
 * stays exactly as it was printed, flagged {@code voided} with its reason, and the money moves on
 * the reversal. Both rows carry the same {@code shift_id}, which is what keeps the shift's Z
 * report adding up to what is actually in the drawer.
 *
 * @param refunded the reversal's {@code total_due} — negative, being money going back out
 */
public record VoidResult(long transactionId,
                         String publicId,
                         long voidedTransactionId,
                         String voidedPublicId,
                         int refunded) {
}
