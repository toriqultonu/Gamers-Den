package dev.gamersden.session.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The floor state machine, edge by edge: {@code OPEN → RUNNING ⇄ PAUSED → LOCKED → CLOSED}
 * (api-contract.md, Sessions).
 *
 * <p>The expectation below is spelled out independently of {@link SessionState} — if the
 * production table is edited, this one has to be edited to match, deliberately. Every one of the
 * 25 ordered pairs is asserted, so an illegal edge cannot be added by accident either.
 */
class SessionStateMachineTest {

    /** The contract's diagram, plus end-from-anywhere and the unlock edge. */
    private static final Map<SessionState, Set<SessionState>> EXPECTED = expected();

    private static Map<SessionState, Set<SessionState>> expected() {
        EnumMap<SessionState, Set<SessionState>> table = new EnumMap<>(SessionState.class);
        // Time can be bought before the clock starts; ending an untimed seat is legal.
        table.put(SessionState.OPEN, EnumSet.of(SessionState.RUNNING, SessionState.CLOSED));
        // Pause by hand, lock by running out, or end once the bill is settled.
        table.put(SessionState.RUNNING,
                EnumSet.of(SessionState.PAUSED, SessionState.LOCKED, SessionState.CLOSED));
        // A paused seat resumes, locks when its last block is returned, or ends.
        table.put(SessionState.PAUSED,
                EnumSet.of(SessionState.RUNNING, SessionState.LOCKED, SessionState.CLOSED));
        // Buying time unlocks to PAUSED — never straight back to RUNNING; staff resume it.
        table.put(SessionState.LOCKED, EnumSet.of(SessionState.PAUSED, SessionState.CLOSED));
        table.put(SessionState.CLOSED, EnumSet.noneOf(SessionState.class));
        return table;
    }

    @ParameterizedTest
    @EnumSource(SessionState.class)
    void everyStateAllowsExactlyItsContractedMoves(SessionState from) {
        assertThat(from.nextStates()).isEqualTo(EXPECTED.get(from));
    }

    @Test
    void allTwentyFivePairsAgreeWithTheTable() {
        for (SessionState from : SessionState.values()) {
            for (SessionState to : SessionState.values()) {
                assertThat(from.canMoveTo(to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(EXPECTED.get(from).contains(to));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(SessionState.class)
    void noStateMovesToItself(SessionState state) {
        assertThat(state.canMoveTo(state)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(SessionState.class)
    void nothingMovesOutOfClosed(SessionState to) {
        assertThat(SessionState.CLOSED.canMoveTo(to)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = SessionState.class, names = {"OPEN", "RUNNING", "PAUSED", "LOCKED"})
    void everyLiveStateCanBeEnded(SessionState from) {
        assertThat(from.canMoveTo(SessionState.CLOSED)).isTrue();
    }

    @Test
    void theIllegalShortcutsTheContractRulesOutStayRuledOut() {
        // No clock without time: OPEN never jumps to PAUSED or LOCKED.
        assertThat(SessionState.OPEN.canMoveTo(SessionState.PAUSED)).isFalse();
        assertThat(SessionState.OPEN.canMoveTo(SessionState.LOCKED)).isFalse();
        // A session never goes back to having no time bought.
        assertThat(SessionState.RUNNING.canMoveTo(SessionState.OPEN)).isFalse();
        assertThat(SessionState.PAUSED.canMoveTo(SessionState.OPEN)).isFalse();
        assertThat(SessionState.LOCKED.canMoveTo(SessionState.OPEN)).isFalse();
        // Out of time means out of time: buying more resumes through PAUSED, never straight on.
        assertThat(SessionState.LOCKED.canMoveTo(SessionState.RUNNING)).isFalse();
        // A closed session is history.
        assertThat(SessionState.CLOSED.canMoveTo(SessionState.RUNNING)).isFalse();
        assertThat(SessionState.CLOSED.canMoveTo(SessionState.OPEN)).isFalse();
    }

    @Test
    void nullIsNotAMove() {
        assertThat(SessionState.OPEN.canMoveTo(null)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(ClockAction.class)
    void everyClockActionRidesALegalEdge(ClockAction action) {
        assertThat(action.from().canMoveTo(action.to()))
                .as("%s: %s -> %s", action, action.from(), action.to())
                .isTrue();
    }

    @Test
    void theClockActionsCoverStartPauseResumeAndNothingElse() {
        assertThat(ClockAction.values())
                .containsExactly(ClockAction.START, ClockAction.PAUSE, ClockAction.RESUME);
        assertThat(ClockAction.START.from()).isEqualTo(SessionState.OPEN);
        assertThat(ClockAction.PAUSE.from()).isEqualTo(SessionState.RUNNING);
        assertThat(ClockAction.RESUME.from()).isEqualTo(SessionState.PAUSED);
    }

    @Test
    void onlyRunningBurnsTimeAndOnlyClosedIsNotLive() {
        for (SessionState state : SessionState.values()) {
            assertThat(state.isClockRunning()).isEqualTo(state == SessionState.RUNNING);
            assertThat(state.isLive()).isEqualTo(state != SessionState.CLOSED);
            assertThat(state.canRunOutOfTime())
                    .isEqualTo(state == SessionState.RUNNING || state == SessionState.PAUSED);
        }
    }
}
