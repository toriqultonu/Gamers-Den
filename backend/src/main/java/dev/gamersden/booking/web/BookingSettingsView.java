package dev.gamersden.booking.web;

import dev.gamersden.booking.domain.BookingSettings;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * {@code GET/PUT /booking-settings} — {@code {enabled, packageFee, cancelCutoffHours}}
 * (api-contract.md, Pre-bookings).
 *
 * <p>{@code updatedBy} / {@code updatedAt} ride along for S10's "last changed by" line; they are
 * audit, not contract, and nothing switches on them.
 */
@Schema(name = "BookingSettings", description = "Pre-booking feature flag, package fee and "
        + "cancellation window. Changes apply to NEW bookings only.")
public record BookingSettingsView(boolean enabled,
                                  int packageFee,
                                  int cancelCutoffHours,
                                  Long updatedBy,
                                  OffsetDateTime updatedAt) {

    public static BookingSettingsView of(BookingSettings settings) {
        return new BookingSettingsView(settings.isEnabled(), settings.getPackageFee(),
                settings.getCancelCutoffHours(), settings.getUpdatedBy(), settings.getUpdatedAt());
    }
}
