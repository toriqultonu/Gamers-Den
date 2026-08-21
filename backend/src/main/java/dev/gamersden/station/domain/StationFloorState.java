package dev.gamersden.station.domain;

/**
 * What the Floor renders on a station card (design.md, StationCard variants). Derived on every
 * read — never stored: {@code stations.status} only knows AVAILABLE vs MAINTENANCE, everything
 * else follows from the live session, a tournament block or a checked-in arrival.
 *
 * <p>{@link #RESERVED} (tournament station block) is filled in by B12 and {@link #BOOKED}
 * (checked-in arrival waiting to be seated) by B16; until then a station with no live session
 * reads {@link #FREE}.
 */
public enum StationFloorState {

    /** No live session — a walk-in can be seated. */
    FREE,

    /** Session created, no time bought yet: the card shows the panel but no countdown. */
    OPEN,

    /** Clock running — the card counts down. */
    RUNNING,

    PAUSED,

    /** Time is up and the bill is owed; the seat cannot be re-let until the session ends. */
    LOCKED,

    /** Held by a tournament station block (B12). */
    RESERVED,

    /** A checked-in booking is waiting to be seated here (B16). */
    BOOKED,

    /** Taken off the floor by an Admin. */
    MAINTENANCE
}
