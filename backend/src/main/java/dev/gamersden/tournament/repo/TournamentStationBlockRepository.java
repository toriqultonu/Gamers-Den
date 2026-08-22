package dev.gamersden.tournament.repo;

import dev.gamersden.tournament.domain.TournamentStationBlock;
import dev.gamersden.tournament.domain.TournamentStationBlockId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TournamentStationBlockRepository
        extends JpaRepository<TournamentStationBlock, TournamentStationBlockId> {

    List<TournamentStationBlock> findByIdTournamentIdOrderByIdStationIdAsc(Long tournamentId);

    void deleteByIdTournamentId(Long tournamentId);

    /**
     * Every console currently held by an event that is still running — the Floor's RESERVED cards
     * and the {@code STATION_RESERVED} guard in one query (docs/tournaments.md §2).
     */
    @Query("SELECT b.id.stationId FROM TournamentStationBlock b JOIN Tournament t "
            + "ON t.id = b.id.tournamentId WHERE t.status IN ('OPEN', 'LIVE')")
    List<Long> findReservedStationIds();

    /** True while any event — running or finished — still lists this console. */
    boolean existsByIdStationId(Long stationId);
}
