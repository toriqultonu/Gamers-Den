package dev.gamersden.session.domain;

import java.util.Collection;

/**
 * Net outstanding on a session (invariant §5.9, docs/backend-architecture.md §6):
 * {@code unpaid session_blocks + unsettled cart}. Prepaid blocks are born carrying the sale's
 * {@code paid_tx_id}, so they never appear here — that is precisely what lets a booking or
 * play-ticket seat end without a second payment.
 *
 * <p>Computed, never stored (invariant §5.4).
 */
public record SessionBalance(int gamingDue, int fnbDue) {

    public static final SessionBalance ZERO = new SessionBalance(0, 0);

    public static SessionBalance of(Collection<SessionBlock> liveBlocks, int unsettledCartTotal) {
        int gaming = liveBlocks.stream()
                .filter(block -> !block.isRemoved())
                .filter(block -> block.getPaidTxId() == null)
                .mapToInt(SessionBlock::getPrice)
                .sum();
        return new SessionBalance(gaming, Math.max(0, unsettledCartTotal));
    }

    public int netOutstanding() {
        return gamingDue + fnbDue;
    }

    /** The end guard: 0 passes, anything owed is 409 {@code SESSION_HAS_BALANCE}. */
    public boolean isSettled() {
        return netOutstanding() <= 0;
    }
}
