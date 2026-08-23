package dev.gamersden.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * {@code booking_settings} — the single row Setup S10 edits (docs/bookings.md §1). The primary key
 * is {@code TRUE} with a {@code CHECK (id)} behind it, so the schema itself guarantees there is
 * exactly one; V003 seeds it, and nothing here ever inserts a second.
 *
 * <p>Every value on this row is a <em>current</em> figure, never a historical one. A booking
 * snapshots {@code package_fee} and {@code cancel_cutoff_hours} onto its own columns at the moment
 * it is sold, so an edit here reaches new bookings only — the fee somebody already paid and the
 * cutoff they were promised cannot move under them (invariant §5.11).
 */
@Entity
@Table(name = "booking_settings")
public class BookingSettings {

    /** The one row's key. {@code CHECK (id)} refuses anything else. */
    public static final boolean ROW_ID = true;

    @Id
    @Column(nullable = false)
    private Boolean id = ROW_ID;

    /**
     * The feature flag. False hides the Bookings nav item and refuses new bookings with 409
     * {@code PREBOOKING_DISABLED} — outstanding ones stay serviceable (docs/bookings.md §7).
     */
    @Column(nullable = false)
    private boolean enabled = true;

    /** ৳ added to every booking on top of its play time. */
    @Column(name = "package_fee", nullable = false)
    private int packageFee = 100;

    /** How long before {@code start_at} cancellation locks. */
    @Column(name = "cancel_cutoff_hours", nullable = false)
    private int cancelCutoffHours = 2;

    @Column(name = "updated_by")
    private Long updatedBy;

    /**
     * Plainly writable, not database-generated: the column's {@code DEFAULT now()} only fires on
     * the insert V003 seeds, and a write has to move it or S10's "last changed" line would sit at
     * the day the venue was installed. {@code BookingSettingsService} stamps it from the server
     * clock, like every other timestamp the application decides (invariant §5.1).
     */
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected BookingSettings() {
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getPackageFee() {
        return packageFee;
    }

    public void setPackageFee(int packageFee) {
        this.packageFee = packageFee;
    }

    public int getCancelCutoffHours() {
        return cancelCutoffHours;
    }

    public void setCancelCutoffHours(int cancelCutoffHours) {
        this.cancelCutoffHours = cancelCutoffHours;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(Long updatedBy) {
        this.updatedBy = updatedBy;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
