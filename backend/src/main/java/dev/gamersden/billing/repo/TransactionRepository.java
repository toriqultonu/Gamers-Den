package dev.gamersden.billing.repo;

import dev.gamersden.billing.domain.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByPublicId(String publicId);

    List<Transaction> findByShiftId(Long shiftId);
}
