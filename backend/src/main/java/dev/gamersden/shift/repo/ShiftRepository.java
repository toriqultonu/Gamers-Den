package dev.gamersden.shift.repo;

import dev.gamersden.shift.domain.Shift;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
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

    /** The most recent closes, newest first — S2's "staff & recent shift closes" rail. */
    List<Shift> findByClosedAtIsNotNullOrderByClosedAtDescIdDesc(Pageable pageable);

    /**
     * Every shift that overlapped the window, oldest first — the hours the venue was trading, and
     * so the denominator S9's station-utilisation bars are a percentage of.
     *
     * <p>An open shift ({@code closedAt IS NULL}) overlaps everything after it opened; the caller
     * clips it at "now".
     */
    @Query("""
            SELECT s FROM Shift s
             WHERE s.openedAt < :to
               AND (s.closedAt IS NULL OR s.closedAt > :from)
             ORDER BY s.openedAt ASC, s.id ASC
            """)
    List<Shift> overlapping(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);
}
