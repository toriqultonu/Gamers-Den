package dev.gamersden.booking.web;

import dev.gamersden.booking.domain.BookingService;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What {@code POST /bookings/{id}/cancel} returns: the booking now CANCELLED and the negative
 * transaction that handed the money back.
 *
 * <p>Stored by the idempotency filter and replayed on a retry, so a double-tapped cancel refunds
 * once and returns the same refund id both times (invariant §5.2).
 *
 * @param refundAmount negative — the direction belongs to the transaction, not to a flag
 *                     (invariant §5.7)
 */
@Schema(name = "BookingCancelled", description = "A cancelled booking and its refund")
public record BookingCancelledView(BookingView booking,
                                   Long refundTransactionId,
                                   String refundPublicId,
                                   int refundAmount) {

    public static BookingCancelledView of(BookingService.Cancelled cancelled) {
        return new BookingCancelledView(BookingView.of(cancelled.booking()),
                cancelled.refund() == null ? null : cancelled.refund().transactionId(),
                cancelled.refund() == null ? null : cancelled.refund().publicId(),
                cancelled.refund() == null ? 0 : cancelled.refund().amount());
    }
}
