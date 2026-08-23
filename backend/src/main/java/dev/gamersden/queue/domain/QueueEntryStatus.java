package dev.gamersden.queue.domain;

/** {@code queue_entries.status} — where one issued token stands (docs/bookings.md §3, §5). */
public enum QueueEntryStatus {

    /** In the queue rail, in token order: who plays next. */
    WAITING,

    /** Seated on a console; {@code session_id} names the seat (B16). */
    SEATED,

    /** A no-show the money was handed back for, kept as the record of the token (B16). */
    REFUNDED
}
