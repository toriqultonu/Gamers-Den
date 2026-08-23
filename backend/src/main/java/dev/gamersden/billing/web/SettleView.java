package dev.gamersden.billing.web;

import dev.gamersden.billing.domain.SettleResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

/**
 * What {@code POST /payments} returns (api-contract.md, "Billing &amp; payments"):
 * {@code {transactionId, printJobId, entryTokens?, queueTokens?}}.
 *
 * <p>This object is what the idempotency filter stores. A retry under the same
 * {@code Idempotency-Key} replays it verbatim with {@code Idempotency-Replayed: true}, which is
 * how "a retried settle can never double-charge or double-print" (§5.2) becomes something the FE
 * can rely on: the same {@code transactionId} and the same {@code printJobId} come back, so a
 * flaky network looks exactly like a slow one. The tokens come back unchanged with them — a
 * replayed play-ticket sale issues no second number.
 *
 * @param publicId    the printed and barcoded id, {@code GD-2608-047} — sent so the success state
 *                    can name the sale without a second round trip
 * @param entryTokens omitted while empty ({@code default-property-inclusion: non_null})
 * @param queueTokens the daily play-queue tokens {@code playTickets[]} issued, in sale order
 */
@Schema(name = "SettleResult", description = "The transaction and the receipt a settle produced")
public record SettleView(long transactionId,
                         String publicId,
                         long printJobId,
                         List<String> entryTokens,
                         List<QueueTokenView> queueTokens) {

    public static SettleView of(SettleResult result) {
        return new SettleView(result.transactionId(), result.publicId(), result.printJobId(),
                result.entryTokens(),
                result.queueTokens() == null ? null : result.queueTokens().stream()
                        .map(QueueTokenView::of).toList());
    }

    /**
     * One issued play-queue token.
     *
     * @param queueEntryId what the Floor calls {@code POST /play-queue/{id}/seat} with; it keeps
     *                     working after a day rollover, which the number alone does not
     * @param tokenNo      printed double-height as {@code TOKEN #NN} on the P6 stub
     */
    @Schema(name = "SettledQueueToken")
    public record QueueTokenView(long queueEntryId, int tokenNo, LocalDate tokenDate) {

        static QueueTokenView of(SettleResult.QueueToken token) {
            return new QueueTokenView(token.queueEntryId(), token.tokenNo(), token.tokenDate());
        }
    }
}
