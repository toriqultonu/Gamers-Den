package dev.gamersden.booking.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /bookings/{id}/cancel} — an optional note for the audit trail.
 *
 * <p>Optional, unlike a void's reason: a void is a correction somebody has to answer for, while a
 * cancel outside the cutoff is the customer exercising the terms they were sold. Blank falls back
 * to "Booking #N cancelled", which is what the refund transaction is logged with.
 */
@Schema(name = "CancelBookingRequest")
public record CancelBookingRequest(@Size(max = 200) String reason) {
}
