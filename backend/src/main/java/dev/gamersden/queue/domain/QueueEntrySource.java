package dev.gamersden.queue.domain;

/**
 * {@code queue_entries.source} — which counter sold the token (docs/bookings.md §5). One daily
 * sequence serves both (invariant §5.10); this column is what tells them apart afterwards.
 */
public enum QueueEntrySource {

    /** Issued at booking check-in — the prepaid time was bought in advance (B15). */
    BOOKING,

    /** Sold at the POS as a walk-up play ticket while every console was busy (B16). */
    PLAY_TICKET
}
