package dev.gamersden.booking.domain;

import dev.gamersden.booking.repo.BookingSettingsRepository;
import dev.gamersden.common.config.VenueTime;
import dev.gamersden.common.error.ConflictException;
import dev.gamersden.common.error.ErrorCode;
import dev.gamersden.common.error.ValidationFailedException;
import dev.gamersden.common.security.CurrentStaff;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;

/**
 * {@code GET/PUT /booking-settings} (docs/bookings.md §1) — the feature flag, the package fee and
 * the cancellation window.
 *
 * <p>Reading is every operator's job: the booking form prices against the fee and the Bookings
 * page hides itself when the feature is off. Writing is Admin only, and the guard lives on
 * {@code BookingSettingsController} — this class assumes it passed.
 *
 * <p><strong>An edit reaches new bookings only</strong> (invariant §5.11). Nothing here writes to
 * a {@code bookings} row, and nothing on a booking is ever re-read from this table: the fee and
 * the cutoff a customer was sold under live on their own row as snapshots. Turning the feature off
 * is the same story from the other end — {@link #requireEnabled()} refuses <em>new</em> bookings
 * with 409 {@code PREBOOKING_DISABLED}, and check-in, seat and cancel never call it, so the
 * bookings already paid for stay serviceable (docs/bookings.md §7).
 */
@Service
public class BookingSettingsService {

    private static final Logger log = LoggerFactory.getLogger(BookingSettingsService.class);

    /** A sanity ceiling on the cancellation window — a week out is already generous. */
    private static final int MAX_CUTOFF_HOURS = 168;

    private final BookingSettingsRepository settings;
    private final Clock clock;

    public BookingSettingsService(BookingSettingsRepository settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * The one row. V003 seeds it, so a missing row means somebody has deleted it by hand — an
     * {@link IllegalStateException} rather than a 404, because there is no request the caller
     * could have made that would have been right.
     */
    @Transactional(readOnly = true)
    public BookingSettings get() {
        return load();
    }

    /**
     * The same row, read inside a money transaction so the fee and the cutoff a booking snapshots
     * are the ones in force at the instant it is written.
     */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public BookingSettings current() {
        return load();
    }

    /** The door check on {@code POST /bookings} — and deliberately nowhere else. */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public BookingSettings requireEnabled() {
        BookingSettings current = load();
        if (!current.isEnabled()) {
            throw new ConflictException(ErrorCode.PREBOOKING_DISABLED,
                    "Pre-booking is switched off — existing bookings still check in and cancel, "
                            + "but no new ones are taken",
                    Map.of("enabled", false));
        }
        return current;
    }

    @Transactional
    public BookingSettings update(Boolean enabled, Integer packageFee, Integer cancelCutoffHours) {
        BookingSettings current = load();
        if (enabled != null) {
            current.setEnabled(enabled);
        }
        if (packageFee != null) {
            current.setPackageFee(requireNotNegative("packageFee", packageFee, Integer.MAX_VALUE));
        }
        if (cancelCutoffHours != null) {
            current.setCancelCutoffHours(
                    requireNotNegative("cancelCutoffHours", cancelCutoffHours, MAX_CUTOFF_HOURS));
        }
        CurrentStaff.find().ifPresent(staff -> current.setUpdatedBy(staff.id()));
        current.setUpdatedAt(VenueTime.now(clock));
        log.info("booking settings set to enabled={} packageFee={} cancelCutoffHours={} by staff {} "
                        + "— new bookings only", current.isEnabled(), current.getPackageFee(),
                current.getCancelCutoffHours(), current.getUpdatedBy());
        return current;
    }

    private BookingSettings load() {
        return settings.findById(BookingSettings.ROW_ID).orElseThrow(() -> new IllegalStateException(
                "booking_settings has no row — V003 seeds it and the schema allows exactly one"));
    }

    private static int requireNotNegative(String field, int value, int max) {
        if (value < 0) {
            throw ValidationFailedException.onField(field, "%s cannot be negative".formatted(field));
        }
        if (value > max) {
            throw ValidationFailedException.onField(field,
                    "%s cannot be more than %d".formatted(field, max));
        }
        return value;
    }
}
