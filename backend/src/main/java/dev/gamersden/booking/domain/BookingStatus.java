package dev.gamersden.booking.domain;

/**
 * {@code bookings.status} — the lifecycle of docs/bookings.md §2.
 *
 * <pre>
 * PAID --check-in--&gt; ARRIVED --seat (Floor)--&gt; USED
 *   \--cancel (>= cutoff h before start)--&gt; CANCELLED
 * </pre>
 *
 * <p>Only {@link #PAID} is still open business: it is the one status that can be checked in or
 * cancelled, and it is the whole of the Upcoming tab. Everything else is history.
 */
public enum BookingStatus {

    /** Paid for, nobody has arrived yet. */
    PAID,

    /** Checked in, holding a daily queue token, waiting for a console (B16 seats it). */
    ARRIVED,

    /** Seated — the prepaid blocks are on a session (B16). */
    USED,

    /** Called off outside the cutoff window; the money went back as a negative transaction. */
    CANCELLED;

    /** True while the booking can still be checked in or cancelled. */
    public boolean isOpen() {
        return this == PAID;
    }

    /** True once the customer has arrived — cancel is no longer the way to give money back. */
    public boolean hasArrived() {
        return this == ARRIVED || this == USED;
    }
}
