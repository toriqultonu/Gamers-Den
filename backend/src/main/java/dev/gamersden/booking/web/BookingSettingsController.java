package dev.gamersden.booking.web;

import dev.gamersden.booking.domain.BookingSettingsService;
import dev.gamersden.common.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /booking-settings} (api-contract.md, Pre-bookings; docs/bookings.md §1) — every operator
 * reads it, only an Admin writes it.
 *
 * <p>The read is open to all three roles because the booking form prices against
 * {@code packageFee}, the cancel button is drawn from {@code cancelCutoffHours}, and the whole
 * Bookings nav item hides itself when {@code enabled} is false. The write is the one booking row
 * ticked for Admin alone in §1's permission matrix, and the API is what enforces that — a Cashier
 * gets 403 whether or not the UI ever offered them the screen.
 *
 * <p>A write reaches <strong>new bookings only</strong>. Nothing behind this controller can touch
 * a {@code bookings} row: the fee and the cutoff a customer was sold under live on their booking
 * as snapshots (invariant §5.11).
 */
@RestController
@RequestMapping("/booking-settings")
@Tag(name = "Pre-bookings")
public class BookingSettingsController {

    private final BookingSettingsService settings;

    public BookingSettingsController(BookingSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "The pre-booking flag, package fee and cancellation window")
    public BookingSettingsView get() {
        return BookingSettingsView.of(settings.get());
    }

    @PutMapping
    @PreAuthorize(Roles.ADMIN)
    @Operation(summary = "Set the pre-booking settings (Admin)",
            description = "Every field optional; omitted fields keep their stored value. New "
                    + "bookings only — existing bookings keep the fee and cutoff they were sold "
                    + "under. Switching enabled off refuses new bookings with 409 "
                    + "PREBOOKING_DISABLED; the ones already paid for still check in and cancel.")
    public BookingSettingsView update(@Valid @RequestBody UpdateBookingSettingsRequest request) {
        return BookingSettingsView.of(settings.update(request.enabled(), request.packageFee(),
                request.cancelCutoffHours()));
    }
}
