package dev.gamersden.tournament.domain;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Match countdown math, server-side (docs/tournaments.md §4, invariant §5.1).
 *
 * <p>One formula, spelled out in §4:
 * {@code remainingSeconds = (duration + extra_min) · 60 − elapsed}. Nothing is stored — a match
 * keeps only the instant it was started and the minutes added to it, and every surface that shows
 * a countdown (the "Now on «console»" tile, the bracket tag, the match board row, the Floor card)
 * recomputes from those two columns and the venue clock. That is why {@code /extend} needs no
 * re-basing logic of its own: adding to {@code extra_min} moves the answer for every reader at
 * once.
 *
 * <p>Pure and static so the arithmetic can be unit-tested without a database, a Spring context or
 * a real clock — the same reason {@code SessionClock} is.
 */
public final class MatchClock {

    public static final int SECONDS_PER_MINUTE = 60;

    private MatchClock() {
    }

    /** The time a match is allowed, added minutes included. */
    public static long allottedSeconds(int matchDurationMin, int extraMin) {
        return Math.max(0, matchDurationMin + extraMin) * (long) SECONDS_PER_MINUTE;
    }

    /** How long the match has been on. 0 until somebody starts it. */
    public static long elapsedSeconds(OffsetDateTime startedAt, OffsetDateTime at) {
        if (startedAt == null) {
            return 0L;
        }
        return Math.max(0L, startedAt.until(at, ChronoUnit.SECONDS));
    }

    /**
     * What the countdown reads, floored at 0 — an overrun is "time up — record the winner", not a
     * negative clock (§4). {@code null} for a match nobody has started: there is no countdown to
     * show, and 0 would render as time up on a match that has not begun.
     */
    public static Long remainingSeconds(TournamentMatch match, int matchDurationMin,
                                        OffsetDateTime at) {
        if (!match.hasStarted() || match.isDecided()) {
            return null;
        }
        return Math.max(0L, allottedSeconds(matchDurationMin, match.getExtraMin())
                - elapsedSeconds(match.getStartedAt(), at));
    }

    /** True while a started, undecided match has run past its allotted time. */
    public static boolean isTimeUp(TournamentMatch match, int matchDurationMin, OffsetDateTime at) {
        Long remaining = remainingSeconds(match, matchDurationMin, at);
        return remaining != null && remaining == 0L;
    }
}
