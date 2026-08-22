package dev.gamersden.session.domain;

import dev.gamersden.common.spi.SessionSettlement;
import dev.gamersden.session.repo.SessionBlockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The {@code session} package's answer to {@link SessionSettlement} — the only door
 * {@code billing} uses to write {@code session_blocks.paid_tx_id} (ARCHITECTURE.md §3).
 *
 * <p>{@link Propagation#MANDATORY} is deliberate: both methods are halves of someone else's money
 * transaction (invariant §5.3) and must never be able to commit on their own. Marking blocks paid
 * outside the transaction that took the money is exactly the bug the one-TX rule exists to
 * prevent.
 */
@Service
public class SessionSettlementService implements SessionSettlement {

    private static final Logger log = LoggerFactory.getLogger(SessionSettlementService.class);

    private final SessionBlockRepository blocks;

    public SessionSettlementService(SessionBlockRepository blocks) {
        this.blocks = blocks;
    }

    /**
     * Every live block still carrying a NULL {@code paid_tx_id} becomes this transaction's. The
     * session is untouched — it keeps its state, its clock and its time; only the money moved.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PaidBlocks markUnpaidBlocksPaid(long sessionId, long txId) {
        List<SessionBlock> unpaid = blocks.findBySessionIdAndRemovedFalseOrderByIdAsc(sessionId).stream()
                .filter(block -> block.getPaidTxId() == null)
                .toList();
        if (unpaid.isEmpty()) {
            return PaidBlocks.NONE;
        }
        int amount = 0;
        for (SessionBlock block : unpaid) {
            block.setPaidTxId(txId);
            amount += block.getPrice();
        }
        log.info("session {} had {} blocks ({} BDT) marked paid by transaction {}",
                sessionId, unpaid.size(), amount, txId);
        return new PaidBlocks(unpaid.size(), amount);
    }

    /**
     * The inverse. Removed blocks are included on purpose: a block that was paid for and later
     * returned still points at the voided transaction, and leaving it pointing at money nobody
     * took would strand it.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int releaseBlocksPaidBy(long txId) {
        List<SessionBlock> paid = blocks.findByPaidTxId(txId);
        paid.forEach(block -> block.setPaidTxId(null));
        if (!paid.isEmpty()) {
            log.info("{} blocks released back to unpaid by the void of transaction {}",
                    paid.size(), txId);
        }
        return paid.size();
    }
}
