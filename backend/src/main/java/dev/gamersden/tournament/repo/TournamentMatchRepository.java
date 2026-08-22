package dev.gamersden.tournament.repo;

import dev.gamersden.tournament.domain.TournamentMatch;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TournamentMatchRepository extends JpaRepository<TournamentMatch, Long> {

    /** The whole bracket in drawing order: first round first, left to right. */
    List<TournamentMatch> findByTournamentIdOrderByRoundAscSlotAsc(Long tournamentId);

    /** Whether a bracket has already been drawn — a second generate is a 409, never a redraw. */
    boolean existsByTournamentId(Long tournamentId);

    /**
     * The match, locked for the rest of the caller's transaction. Two terminals recording the same
     * result at once must queue here: without the lock both read an undecided match, both write a
     * winner, and both propagate — advancing two different players into the same next slot.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM TournamentMatch m WHERE m.id = :id")
    Optional<TournamentMatch> findByIdForUpdate(@Param("id") Long id);
}
