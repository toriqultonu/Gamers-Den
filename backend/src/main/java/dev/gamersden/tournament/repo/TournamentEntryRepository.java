package dev.gamersden.tournament.repo;

import dev.gamersden.tournament.domain.TournamentEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TournamentEntryRepository extends JpaRepository<TournamentEntry, Long> {

    List<TournamentEntry> findByTournamentIdOrderBySeedAsc(Long tournamentId);

    Optional<TournamentEntry> findByQrToken(String qrToken);

    /** How many seeds this event has handed out — read under the tournament's row lock. */
    int countByTournamentId(Long tournamentId);

    /** The entries a cancel has to refund, grouped by the sale that paid for them. */
    @Query("SELECT e FROM TournamentEntry e WHERE e.tournamentId = :tournamentId "
            + "AND e.refunded = FALSE ORDER BY e.txId ASC, e.seed ASC")
    List<TournamentEntry> findRefundableOf(@Param("tournamentId") Long tournamentId);
}
