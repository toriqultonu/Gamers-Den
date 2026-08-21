package dev.gamersden.session.domain;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * One session as the floor reads it — the row, its live blocks and everything derived from them
 * at a single instant. {@code serverTime} travels with the payload so a client can keep counting
 * down between polls without ever trusting its own clock (invariant §5.1).
 */
public record SessionDetail(Session session,
                            List<SessionBlock> blocks,
                            SessionState state,
                            SessionBalance balance,
                            long purchasedSeconds,
                            long consumedSeconds,
                            long remainingSeconds,
                            OffsetDateTime serverTime) {

    public static SessionDetail of(Session session,
                                   List<SessionBlock> liveBlocks,
                                   SessionBalance balance,
                                   OffsetDateTime at) {
        int count = liveBlocks.size();
        return new SessionDetail(session, List.copyOf(liveBlocks),
                SessionClock.effectiveState(session, count, at), balance,
                SessionClock.purchasedSeconds(count),
                SessionClock.consumedSeconds(session, at),
                SessionClock.remainingSeconds(session, count, at), at);
    }

    public int blockCount() {
        return blocks.size();
    }

    /** Blocks already carrying a {@code paid_tx_id} — settled mid-session, or prepaid. */
    public int paidBlocks() {
        return (int) blocks.stream().filter(block -> block.getPaidTxId() != null).count();
    }

    public int unpaidBlocks() {
        return blockCount() - paidBlocks();
    }
}
