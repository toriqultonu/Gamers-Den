package dev.gamersden.billing.web;

import dev.gamersden.billing.domain.SettleResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * What {@code POST /payments} returns (api-contract.md, "Billing &amp; payments"):
 * {@code {transactionId, printJobId, entryTokens?, queueTokens?}}.
 *
 * <p>This object is what the idempotency filter stores. A retry under the same
 * {@code Idempotency-Key} replays it verbatim with {@code Idempotency-Replayed: true}, which is
 * how "a retried settle can never double-charge or double-print" (§5.2) becomes something the FE
 * can rely on: the same {@code transactionId} and the same {@code printJobId} come back, so a
 * flaky network looks exactly like a slow one.
 *
 * @param publicId    the printed and barcoded id, {@code GD-2608-047} — sent so the success state
 *                    can name the sale without a second round trip
 * @param entryTokens omitted while empty ({@code default-property-inclusion: non_null}); B12 fills it
 * @param queueTokens likewise, B16
 */
@Schema(name = "SettleResult", description = "The transaction and the receipt a settle produced")
public record SettleView(long transactionId,
                         String publicId,
                         long printJobId,
                         List<String> entryTokens,
                         List<String> queueTokens) {

    public static SettleView of(SettleResult result) {
        return new SettleView(result.transactionId(), result.publicId(), result.printJobId(),
                result.entryTokens(), result.queueTokens());
    }
}
