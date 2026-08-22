package dev.gamersden.billing.web;

import dev.gamersden.billing.domain.VoidResult;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What {@code POST /payments/{id}/void} returns. A void produces a <em>new</em> transaction rather
 * than editing the old one (invariant §5.7), so both are named here: the reversal that now holds
 * the money, and the sale it cancelled, which stays in the books exactly as it was printed.
 *
 * @param refunded the reversal's total — negative, being money going back out
 */
@Schema(name = "VoidResult", description = "The reversal transaction a void produced")
public record VoidView(long transactionId,
                       String publicId,
                       long voidedTransactionId,
                       String voidedPublicId,
                       int refunded) {

    public static VoidView of(VoidResult result) {
        return new VoidView(result.transactionId(), result.publicId(), result.voidedTransactionId(),
                result.voidedPublicId(), result.refunded());
    }
}
