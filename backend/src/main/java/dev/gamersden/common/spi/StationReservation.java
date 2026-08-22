package dev.gamersden.common.spi;

import java.time.OffsetDateTime;
import java.util.Set;

/**
 * The narrow read the {@code station} and {@code session} packages need from {@code tournament} —
 * which consoles a running event is holding (docs/tournaments.md §2).
 *
 * <p>One rule decides all three methods: a station is reserved <em>iff</em> it is listed in
 * {@code tournament_station_blocks} for a tournament whose status is {@code OPEN} or {@code LIVE}.
 * Reservation is therefore derived, never stored (invariant §5.4) — finishing or cancelling an
 * event releases its consoles without a second flag to clear.
 *
 * <p>Implemented by {@code tournament/domain/TournamentReservationLookup}.
 */
public interface StationReservation {

    /**
     * True while a tournament station block covers this station at {@code at}.
     *
     * <p>{@code at} is part of the signature because a walk-in session is refused <em>as of</em>
     * the moment it is being opened; the block itself has no window of its own — a manager blocks
     * a console when they want it held and the event's status is what lets it go again.
     */
    boolean isReserved(long stationId, OffsetDateTime at);

    /** Every reserved console in one read — the Floor grid's RESERVED cards (design.md S3). */
    Set<Long> reservedStationIds();

    /**
     * True while any event — running or long finished — still lists this console. Deleting the
     * station would orphan the block row, so {@code station} refuses with 409
     * {@code STATION_IN_USE} and offers maintenance instead.
     */
    boolean isBlockedByAnyTournament(long stationId);
}
