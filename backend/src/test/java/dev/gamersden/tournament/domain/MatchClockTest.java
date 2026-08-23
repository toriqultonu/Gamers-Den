package dev.gamersden.tournament.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The match countdown, without a database (docs/tournaments.md §4, invariant §5.1).
 *
 * <p>The formula is one line — {@code (duration + extra_min) · 60 − elapsed} — and everything
 * interesting about it is at the edges: what a match nobody started reads, what happens past zero,
 * and what adding time does to a clock that has already run out. Those are the cases here.
 */
class MatchClockTest {

    private static final OffsetDateTime START =
            OffsetDateTime.of(2026, 3, 14, 19, 0, 0, 0, ZoneOffset.ofHoursMinutes(6, 0));

    @Nested
    @DisplayName("a match nobody is playing has no countdown")
    class NoCountdown {

        @Test
        void unstartedReadsNull() {
            TournamentMatch match = match();

            assertThat(MatchClock.remainingSeconds(match, 20, START)).isNull();
            assertThat(MatchClock.isTimeUp(match, 20, START))
                    .as("0 would render as time up on a match that has not begun")
                    .isFalse();
        }

        @Test
        void decidedReadsNull() {
            TournamentMatch match = match();
            match.setStartedAt(START);
            match.setWinnerEntry(1L);

            assertThat(MatchClock.remainingSeconds(match, 20, START.plusMinutes(5))).isNull();
            assertThat(MatchClock.isTimeUp(match, 20, START.plusMinutes(30))).isFalse();
        }
    }

    @Nested
    @DisplayName("a started match counts down from its allotted time")
    class Countdown {

        @Test
        void fullAtTheMomentItStarts() {
            TournamentMatch match = started();

            assertThat(MatchClock.remainingSeconds(match, 20, START)).isEqualTo(20 * 60);
        }

        @Test
        void elapsedComesOff() {
            TournamentMatch match = started();

            assertThat(MatchClock.remainingSeconds(match, 20, START.plusMinutes(7).plusSeconds(30)))
                    .isEqualTo(12 * 60 + 30);
        }

        @Test
        @DisplayName("past zero it floors, and reads time up rather than counting backwards")
        void neverNegative() {
            TournamentMatch match = started();

            assertThat(MatchClock.remainingSeconds(match, 20, START.plusMinutes(45))).isZero();
            assertThat(MatchClock.isTimeUp(match, 20, START.plusMinutes(45))).isTrue();
            assertThat(MatchClock.isTimeUp(match, 20, START.plusMinutes(20)))
                    .as("equal counts as used up").isTrue();
            assertThat(MatchClock.isTimeUp(match, 20, START.plusMinutes(19))).isFalse();
        }
    }

    @Nested
    @DisplayName("added minutes re-base the same read")
    class AddedTime {

        @Test
        void extraMinutesMoveTheAnswerForEveryReader() {
            TournamentMatch match = started();
            OffsetDateTime at = START.plusMinutes(18);
            assertThat(MatchClock.remainingSeconds(match, 20, at)).isEqualTo(2 * 60);

            match.setExtraMin(5);

            assertThat(MatchClock.remainingSeconds(match, 20, at))
                    .as("nothing was re-stamped — the same started_at now yields 7 minutes")
                    .isEqualTo(7 * 60);
            assertThat(match.getStartedAt()).isEqualTo(START);
        }

        @Test
        @DisplayName("a match already past zero comes back with the time it was given")
        void extendingRevivesAnExpiredMatch() {
            TournamentMatch match = started();
            OffsetDateTime at = START.plusMinutes(23);
            assertThat(MatchClock.isTimeUp(match, 20, at)).isTrue();

            match.setExtraMin(5);

            assertThat(MatchClock.remainingSeconds(match, 20, at)).isEqualTo(2 * 60);
            assertThat(MatchClock.isTimeUp(match, 20, at)).isFalse();
        }

        @Test
        void repeatedExtensionsAccumulate() {
            assertThat(MatchClock.allottedSeconds(20, 5 + 5 + 10)).isEqualTo(40 * 60);
        }
    }

    @Test
    @DisplayName("elapsed is measured forwards only — a clock that went backwards reads 0")
    void elapsedNeverGoesNegative() {
        assertThat(MatchClock.elapsedSeconds(null, START)).isZero();
        assertThat(MatchClock.elapsedSeconds(START, START.minusMinutes(1))).isZero();
        assertThat(MatchClock.elapsedSeconds(START, START.plusSeconds(90))).isEqualTo(90);
    }

    private static TournamentMatch match() {
        return new TournamentMatch(1L, 1, 1, 10L, 11L, 99L);
    }

    private static TournamentMatch started() {
        TournamentMatch match = match();
        match.setStartedAt(START);
        return match;
    }
}
