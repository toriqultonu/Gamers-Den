package dev.gamersden.booking.web;

import dev.gamersden.booking.domain.BookingService;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * What {@code POST /bookings} returns: the booking, the sale that paid for it and the job carrying
 * its P1 receipt and P7 confirmation.
 *
 * <p>This object is what the idempotency filter stores, so a retry under the same
 * {@code Idempotency-Key} replays it verbatim — the same booking, the same transaction and the
 * same print job come back, and the customer is charged exactly once (invariant §5.2).
 *
 * @param overlappingBookingIds live bookings this one runs into on the same console. A warning the
 *                              operator has already overridden by confirming, not a refusal
 *                              (docs/bookings.md §7); empty on a clean slot.
 */
@Schema(name = "BookingCreated", description = "A booking, paid for and held")
public record BookingCreatedView(BookingView booking,
                                 long transactionId,
                                 String publicId,
                                 long printJobId,
                                 List<Long> overlappingBookingIds) {

    public static BookingCreatedView of(BookingService.Created created) {
        return new BookingCreatedView(BookingView.of(created.booking()), created.transactionId(),
                created.publicId(), created.printJobId(), created.overlappingBookingIds());
    }
}
