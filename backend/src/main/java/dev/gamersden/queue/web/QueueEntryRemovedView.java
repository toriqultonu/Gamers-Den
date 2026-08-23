package dev.gamersden.queue.web;

import dev.gamersden.queue.domain.PlayQueueService;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What {@code DELETE /play-queue/{id}} returns: the token, now REFUNDED, and the negative
 * transaction that handed the money back (api-contract.md, "Play queue").
 *
 * <p>The row is not deleted. The token was issued and paid for, and the refund hangs off that
 * record — reconciliation is structural (invariant §5.7), so the queue keeps its history and the
 * rail simply stops listing it.
 *
 * @param refund {@code null} only when the token was somehow sold for nothing at all
 */
@Schema(name = "QueueEntryRemoved", description = "A no-show refunded and taken out of the queue")
public record QueueEntryRemovedView(QueueEntryView entry, RefundView refund) {

    public static QueueEntryRemovedView of(PlayQueueService.Removed removed) {
        return new QueueEntryRemovedView(QueueEntryView.of(removed.entry()),
                removed.refund() == null ? null : new RefundView(removed.refund().transactionId(),
                        removed.refund().publicId(), removed.refund().amount()));
    }

    /** @param amount the negative {@code total_due} that was written */
    @Schema(name = "QueueRefund")
    public record RefundView(long transactionId, String publicId, int amount) {
    }
}
