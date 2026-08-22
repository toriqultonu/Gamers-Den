package dev.gamersden.shift.repo;

import dev.gamersden.shift.domain.Expense;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExpenseRepository extends JpaRepository<Expense, Long> {

    /** Newest first — what S8's petty-cash table lists. */
    List<Expense> findByShiftIdOrderByIdDesc(Long shiftId);

    /** Chronological — the order the X/Z report's expense block prints in. */
    List<Expense> findByShiftIdOrderByIdAsc(Long shiftId);
}
