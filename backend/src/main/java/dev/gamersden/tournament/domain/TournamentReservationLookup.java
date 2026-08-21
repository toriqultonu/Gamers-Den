package dev.gamersden.tournament.domain;

import dev.gamersden.common.spi.StationReservation;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/**
 * The {@code tournament} package's answer to {@link StationReservation}. Nothing reserves a station
 * yet — {@code tournament_station_blocks} lands in V002 with B12 — so this says "free" and the
 * 409 {@code STATION_RESERVED} branch already wired into {@code SessionService} stays dormant.
 *
 * <p>B12 replaces the body with a query over the live blocks; no caller changes.
 */
@Service
public class TournamentReservationLookup implements StationReservation {

    @Override
    public boolean isReserved(long stationId, OffsetDateTime at) {
        return false;
    }
}
