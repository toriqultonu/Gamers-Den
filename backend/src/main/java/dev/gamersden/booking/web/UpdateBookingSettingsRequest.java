package dev.gamersden.booking.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;

/**
 * {@code PUT /booking-settings} — Admin only (api-contract.md §1's matrix: "Pre-booking settings"
 * is the one booking row ticked for Admin alone).
 *
 * <p>Every field optional; an omitted field keeps its stored value, so switching the feature off
 * does not need the fee and the cutoff re-sent. Whatever changes reaches <strong>new bookings
 * only</strong> — existing rows carry their own snapshots (docs/bookings.md §1).
 */
@Schema(name = "UpdateBookingSettingsRequest")
public record UpdateBookingSettingsRequest(Boolean enabled,
                                           @Min(0) Integer packageFee,
                                           @Min(0) Integer cancelCutoffHours) {
}
