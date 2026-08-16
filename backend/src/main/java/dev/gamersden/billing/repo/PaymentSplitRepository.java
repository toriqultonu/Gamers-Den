package dev.gamersden.billing.repo;

import dev.gamersden.billing.domain.PaymentSplit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentSplitRepository extends JpaRepository<PaymentSplit, Long> {

    List<PaymentSplit> findByTxId(Long txId);
}
