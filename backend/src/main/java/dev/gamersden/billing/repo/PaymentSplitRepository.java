package dev.gamersden.billing.repo;

import dev.gamersden.billing.domain.PaymentSplit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface PaymentSplitRepository extends JpaRepository<PaymentSplit, Long> {

    List<PaymentSplit> findByTxId(Long txId);

    /** Every tender of a whole shift in one round trip — the X/Z report's other axis (B11). */
    List<PaymentSplit> findByTxIdInOrderByIdAsc(Collection<Long> txIds);
}
