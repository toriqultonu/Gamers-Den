package dev.gamersden.session.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * {@code sessions.state} — the floor state machine (api-contract.md, Sessions):
 * {@code OPEN → RUNNING ⇄ PAUSED → LOCKED → CLOSED}.
 *
 * <p>The table below <em>is</em> that diagram, plus the two edges it leaves implicit:
 *
 * <ul>
 *   <li>{@code CLOSED} is reachable from every live state — {@code POST /sessions/{id}/end} is
 *       allowed whenever net outstanding is 0, not only after the clock has run out;</li>
 *   <li>{@code LOCKED → PAUSED} is the unlock edge: buying another block on a session whose time
 *       ran out gives it time again, with the clock stopped until staff resume it.</li>
 * </ul>
 *
 * <p>Every write goes through {@link #canMoveTo}, so an illegal move is a programming error that
 * fails in tests rather than a bad row that reaches the floor.
 */
public enum SessionState {

    /** Seat taken, no time bought yet — the panel shows, the countdown does not. */
    OPEN,

    /** Clock running; {@code running_since} is set and the card counts down. */
    RUNNING,

    /** Clock stopped mid-session; the remaining time is frozen in {@code consumed_sec}. */
    PAUSED,

    /** Purchased time is exhausted. The seat is held until the session is ended. */
    LOCKED,

    /** Ended and settled. Terminal — nothing moves out of here. */
    CLOSED;

    private static final Map<SessionState, Set<SessionState>> LEGAL = legalMoves();

    private static Map<SessionState, Set<SessionState>> legalMoves() {
        EnumMap<SessionState, Set<SessionState>> moves = new EnumMap<>(SessionState.class);
        moves.put(OPEN, EnumSet.of(RUNNING, CLOSED));
        moves.put(RUNNING, EnumSet.of(PAUSED, LOCKED, CLOSED));
        moves.put(PAUSED, EnumSet.of(RUNNING, LOCKED, CLOSED));
        moves.put(LOCKED, EnumSet.of(PAUSED, CLOSED));
        moves.put(CLOSED, EnumSet.noneOf(SessionState.class));
        return Map.copyOf(moves);
    }

    /** Legal next states — a self-move is never one of them. */
    public Set<SessionState> nextStates() {
        return LEGAL.get(this);
    }

    public boolean canMoveTo(SessionState next) {
        return next != null && LEGAL.get(this).contains(next);
    }

    /** Everything but {@link #CLOSED} — a live session holds its seat (the DDL's partial index). */
    public boolean isLive() {
        return this != CLOSED;
    }

    /** Only {@link #RUNNING} burns time; every other state freezes the countdown. */
    public boolean isClockRunning() {
        return this == RUNNING;
    }

    /** The two states that hold purchased time and can therefore run out of it. */
    public boolean canRunOutOfTime() {
        return this == RUNNING || this == PAUSED;
    }
}
