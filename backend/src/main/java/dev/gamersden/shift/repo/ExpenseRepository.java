package dev.gamersden.shift.repo;

import dev.gamersden.shift.domain.Expense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public interface ExpenseRepository extends JpaRepository<Expense, Long> {

    /** Newest first — what S8's petty-cash table lists. */
    List<Expense> findByShiftIdOrderByIdDesc(Long shiftId);

    /** Chronological — the order the X/Z report's expense block prints in. */
    List<Expense> findByShiftIdOrderByIdAsc(Long shiftId);

    /**
     * Petty cash folded by the venue day it was spent on — the subtrahend in every "net profit =
     * takings - expenses" figure S2 and S9 show.
     *
     * <p>Bounded by instants so the window rides {@code created_at}, grouped in {@code Asia/Dhaka}
     * so a 1am expense lands on the venue's day rather than UTC's (invariant §5.1).
     */
    @Query(value = """
            SELECT (e.created_at AT TIME ZONE 'Asia/Dhaka')::date AS day,
                   COALESCE(SUM(e.amount), 0)                     AS total
              FROM expenses e
             WHERE e.created_at >= :from AND e.created_at < :to
             GROUP BY day
             ORDER BY day
            """, nativeQuery = true)
    List<DailyExpenseRow> dailyTotals(@Param("from") OffsetDateTime from,
                                      @Param("to") OffsetDateTime to);

    /** Projection for {@link #dailyTotals}. */
    interface DailyExpenseRow {
        LocalDate getDay();

        int getTotal();
    }
}
