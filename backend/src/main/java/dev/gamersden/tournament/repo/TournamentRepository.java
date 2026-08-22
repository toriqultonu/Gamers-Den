package dev.gamersden.tournament.repo;

import dev.gamersden.tournament.domain.Tournament;
import dev.gamersden.tournament.domain.TournamentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TournamentRepository extends JpaRepository<Tournament, Long> {

    boolean existsByName(String name);

    List<Tournament> findByStatusInOrderByScheduledAtAsc(Collection<TournamentStatus> statuses);

    List<Tournament> findByStatusInOrderByScheduledAtDesc(Collection<TournamentStatus> statuses);

    /**
     * The row, locked for the rest of the caller's transaction. Seeding is "next in sale order",
     * so two terminals selling the last two slots at once must queue here — otherwise both read
     * the same entry count, both believe there is room, and one loses at the
     * {@code UNIQUE (tournament_id, seed)} index after the money has already been written.
     *
     * <p>The same lock is what makes {@code TOURNAMENT_FULL} honest: capacity is decided against
     * the count as it is under the lock, not as the menu card quoted it.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Tournament t WHERE t.id = :id")
    Optional<Tournament> findByIdForUpdate(@Param("id") Long id);
}
