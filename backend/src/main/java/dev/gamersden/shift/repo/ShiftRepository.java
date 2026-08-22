package dev.gamersden.shift.repo;

import dev.gamersden.shift.domain.Shift;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ShiftRepository extends JpaRepository<Shift, Long> {

    Optional<Shift> findByTerminalAndClosedAtIsNull(String terminal);

    boolean existsByStaffIdAndClosedAtIsNull(Long staffId);

    /**
     * The open shift, locked for the rest of the caller's transaction. Two terminals — or two
     * taps — racing to close the same shift would otherwise both read it open, and the second
     * would overwrite the first's Z snapshot with figures that already include its own close.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Shift s WHERE s.terminal = :terminal AND s.closedAt IS NULL")
    Optional<Shift> findOpenByTerminalForUpdate(@Param("terminal") String terminal);
}
