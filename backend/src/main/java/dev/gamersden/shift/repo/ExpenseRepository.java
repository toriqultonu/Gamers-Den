package dev.gamersden.shift.repo;

import dev.gamersden.shift.domain.Expense;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExpenseRepository extends JpaRepository<Expense, Long> {

    List<Expense> findByShiftIdOrderByIdDesc(Long shiftId);
}
