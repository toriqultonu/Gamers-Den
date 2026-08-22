package dev.gamersden.tournament.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/** Composite key of {@code tournament_station_blocks}: one row per console per event. */
@Embeddable
public class TournamentStationBlockId implements Serializable {

    @Column(name = "tournament_id", nullable = false)
    private Long tournamentId;

    @Column(name = "station_id", nullable = false)
    private Long stationId;

    protected TournamentStationBlockId() {
    }

    public TournamentStationBlockId(Long tournamentId, Long stationId) {
        this.tournamentId = tournamentId;
        this.stationId = stationId;
    }

    public Long getTournamentId() {
        return tournamentId;
    }

    public Long getStationId() {
        return stationId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof TournamentStationBlockId that
                && Objects.equals(tournamentId, that.tournamentId)
                && Objects.equals(stationId, that.stationId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tournamentId, stationId);
    }
}
