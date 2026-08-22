package dev.gamersden.tournament.domain;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * {@code tournament_station_blocks} — one row per console an event holds
 * (docs/tournaments.md §2). The row is the whole fact: a station is reserved iff it is listed here
 * for a tournament whose status still {@link TournamentStatus#holdsStations() holds stations}, so
 * finishing or cancelling releases the seat without touching this table.
 *
 * <p>Concurrent events are safe by construction — each draws consoles only from its own rows.
 */
@Entity
@Table(name = "tournament_station_blocks")
public class TournamentStationBlock {

    @EmbeddedId
    private TournamentStationBlockId id;

    protected TournamentStationBlock() {
    }

    public TournamentStationBlock(Long tournamentId, Long stationId) {
        this.id = new TournamentStationBlockId(tournamentId, stationId);
    }

    public TournamentStationBlockId getId() {
        return id;
    }

    public Long getTournamentId() {
        return id.getTournamentId();
    }

    public Long getStationId() {
        return id.getStationId();
    }
}
