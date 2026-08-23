package dev.gamersden.common.events;

/**
 * What a service says happened, not what the browser is sent.
 *
 * <p>The split is deliberate and it is what keeps §4.5 buildable without breaking §3's layering.
 * A payload has to equal a GET shape, and GET shapes are {@code web/} records assembled from a
 * package's own service — so {@code SessionService} cannot build a {@code StationView} and
 * {@code MatchExecutionService} cannot build a {@code TournamentDetailView}. Instead a domain
 * service publishes one of these facts, and the owning package's {@code web/} listener turns it
 * into the payload with the very code its controller answers the GET with.
 *
 * <p>Every one of them is an id and nothing more, which is the second reason: the listener runs
 * <em>after commit</em>, so it must read the committed row rather than trust a snapshot taken
 * while the transaction was still open.
 */
public sealed interface LiveChange {

    /** A Floor card moved — a session, its clock or its blocks, or a match on that console. */
    record StationChanged(long stationId) implements LiveChange {
    }

    /** A token was issued, seated or refunded; the rail is re-read whole. */
    record QueueChanged() implements LiveChange {
    }

    /** One booking was created, checked in, cancelled or used. */
    record BookingChanged(long bookingId) implements LiveChange {
    }

    /** One event changed — configuration, entries, bracket or a match. */
    record TournamentChanged(long tournamentId) implements LiveChange {
    }

    /** A row landed on the operator feed. */
    record AlertRaised(long alertId) implements LiveChange {
    }

    /** A device changed state, or the venue's default printer was reassigned. */
    record PrinterStatusChanged() implements LiveChange {
    }

    /** The outbox moved (B22). */
    record SyncStatusChanged() implements LiveChange {
    }
}
