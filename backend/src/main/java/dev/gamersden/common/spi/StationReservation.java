package dev.gamersden.common.spi;

import java.time.OffsetDateTime;

/**
 * The narrow read the {@code session} package needs from {@code tournament} — a station held by a
 * live tournament block refuses walk-in sessions with 409 {@code STATION_RESERVED}
 * (docs/backend-architecture.md §6).
 *
 * <p>Implemented by {@code tournament/domain/TournamentReservationLookup}, which answers "never
 * reserved" until B12 brings {@code tournament_station_blocks}. The 409 itself is already wired in
 * {@code SessionService}, so B12 only has to make this method tell the truth.
 */
public interface StationReservation {

    /** True while a tournament station block covers this station at {@code at}. */
    boolean isReserved(long stationId, OffsetDateTime at);
}
