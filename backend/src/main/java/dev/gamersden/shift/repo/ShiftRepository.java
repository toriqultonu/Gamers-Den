package dev.gamersden.shift.repo;

import dev.gamersden.shift.domain.Shift;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ShiftRepository extends JpaRepository<Shift, Long> {

    Optional<Shift> findByTerminalAndClosedAtIsNull(String terminal);

    boolean existsByStaffIdAndClosedAtIsNull(Long staffId);
}
