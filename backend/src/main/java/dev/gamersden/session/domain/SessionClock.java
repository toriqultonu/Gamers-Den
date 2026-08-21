package dev.gamersden.session.domain;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Block math, server-side (invariant §5.1). Nothing here is stored: a session keeps only the time
 * already banked in {@code consumed_sec} and, while RUNNING, the {@code running_since} stamp of
 * the current stretch. Everything a client renders — {@code remainingSeconds}, the LOCKED flip —
 * is recomputed from those two columns and the venue clock on every read.
 *
 * <p>Pure and static on purpose: the pause/resume arithmetic is the part that must never drift,
 * so it is unit-tested without a database, a Spring context or a real clock.
 */
public final class SessionClock {

    private SessionClock() {
    }

    /** Bought time: every non-removed block is 30 minutes, whoever paid for it. */
    public static long purchasedSeconds(int blocks) {
        return Math.max(0, blocks) * SessionBlock.SECONDS;
    }

    /**
     * The stretch since the clock was last started or resumed — 0 unless the session is RUNNING,
     * which is exactly what freezes a paused countdown.
     */
    public static long elapsedSinceResume(Session session, OffsetDateTime at) {
        if (session.getRunningSince() == null) {
            return 0L;
        }
        return Math.max(0L, session.getRunningSince().until(at, ChronoUnit.SECONDS));
    }

    /** Banked time plus the running stretch: what the customer has actually played. */
    public static long consumedSeconds(Session session, OffsetDateTime at) {
        return session.getConsumedSec() + elapsedSinceResume(session, at);
    }

    /** Never negative — an overrun reads as 0 remaining, and the session locks. */
    public static long remainingSeconds(Session session, int blocks, OffsetDateTime at) {
        return Math.max(0L, purchasedSeconds(blocks) - consumedSeconds(session, at));
    }

    /** True once the purchased time is used up; equal counts as used up. */
    public static boolean isOutOfTime(Session session, int blocks, OffsetDateTime at) {
        return purchasedSeconds(blocks) <= consumedSeconds(session, at);
    }

    /**
     * What the floor should show right now. The stored state lags by at most one sweeper tick
     * (see {@code SessionExpiryScheduler}), so reads derive the LOCKED flip instead of waiting
     * for it: a countdown that hits zero locks the card the moment it is looked at.
     */
    public static SessionState effectiveState(Session session, int blocks, OffsetDateTime at) {
        if (session.getState().canRunOutOfTime() && isOutOfTime(session, blocks, at)) {
            return SessionState.LOCKED;
        }
        return session.getState();
    }

    /**
     * The time to bank when the clock stops — capped at what was bought, so a session that ran
     * past its last block does not record minutes nobody paid for.
     */
    public static int bankedSecondsOnStop(Session session, int blocks, OffsetDateTime at) {
        return (int) Math.min(purchasedSeconds(blocks), consumedSeconds(session, at));
    }
}
