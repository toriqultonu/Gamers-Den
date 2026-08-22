package dev.gamersden.tournament.domain;

import dev.gamersden.common.spi.StationReservation;
import dev.gamersden.tournament.repo.TournamentStationBlockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * The {@code tournament} package's answer to {@link StationReservation} (ARCHITECTURE.md §3).
 *
 * <p>One query answers all three questions, because the schema states the rule directly: a station
 * is reserved iff it is listed in {@code tournament_station_blocks} for a tournament with status
 * {@code OPEN} or {@code LIVE} (docs/tournaments.md §2). Nothing is stored on the station itself —
 * cancelling or finishing an event releases its consoles by moving the event's status, and the
 * Floor tells the truth on the next read (invariant §5.4).
 */
@Service
public class TournamentReservationLookup implements StationReservation {

    private final TournamentStationBlockRepository blocks;

    public TournamentReservationLookup(TournamentStationBlockRepository blocks) {
        this.blocks = blocks;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isReserved(long stationId, OffsetDateTime at) {
        return reservedStationIds().contains(stationId);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Long> reservedStationIds() {
        return new HashSet<>(blocks.findReservedStationIds());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isBlockedByAnyTournament(long stationId) {
        return blocks.existsByIdStationId(stationId);
    }
}
