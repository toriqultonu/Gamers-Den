package dev.gamersden.session.domain;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Block math across pause and resume (invariant §5.1). A session keeps two columns —
 * {@code consumed_sec} (time already banked) and {@code running_since} (the start of the current
 * stretch, set only while RUNNING) — and every countdown is rebuilt from them.
 *
 * <p>No database, no Spring, no real clock: the arithmetic that decides when a customer's time is
 * up is the part that must never drift.
 */
class SessionClockTest {

    private static final OffsetDateTime T0 =
            OffsetDateTime.of(2026, 8, 21, 18, 0, 0, 0, ZoneOffset.ofHours(6));

    @Test
    void aFreshSessionHasBoughtNothingAndBurnedNothing() {
        Session session = openSession();

        assertThat(SessionClock.purchasedSeconds(0)).isZero();
        assertThat(SessionClock.consumedSeconds(session, T0)).isZero();
        assertThat(SessionClock.remainingSeconds(session, 0, T0)).isZero();
        assertThat(SessionClock.effectiveState(session, 0, T0)).isEqualTo(SessionState.OPEN);
    }

    @Test
    void aBlockIsThirtyMinutes() {
        assertThat(SessionBlock.MINUTES).isEqualTo(30);
        assertThat(SessionClock.purchasedSeconds(1)).isEqualTo(1800);
        assertThat(SessionClock.purchasedSeconds(4)).isEqualTo(7200);
    }

    @Test
    void anOpenSessionsCountdownDoesNotMoveNoMatterHowLongItSits() {
        Session session = openSession();
        // Two blocks bought, clock never started: an hour of wall time changes nothing.
        assertThat(SessionClock.remainingSeconds(session, 2, T0.plusHours(1))).isEqualTo(3600);
    }

    @Test
    void aRunningSessionBurnsWallTimeFromRunningSince() {
        Session session = running(0, T0);

        assertThat(SessionClock.consumedSeconds(session, T0.plusMinutes(10))).isEqualTo(600);
        assertThat(SessionClock.remainingSeconds(session, 2, T0.plusMinutes(10))).isEqualTo(3000);
    }

    @Test
    void pausingBanksTheStretchAndResumingStartsANewOne() {
        // Two blocks (3600 s). Play 10 min, pause, sit paused for an hour, resume, play 5 more.
        Session session = running(0, T0);

        int bankedAtPause = SessionClock.bankedSecondsOnStop(session, 2, T0.plusMinutes(10));
        assertThat(bankedAtPause).isEqualTo(600);
        session.setConsumedSec(bankedAtPause);
        session.setRunningSince(null);
        session.setState(SessionState.PAUSED);

        OffsetDateTime muchLater = T0.plusMinutes(70);
        assertThat(SessionClock.consumedSeconds(session, muchLater)).isEqualTo(600);
        assertThat(SessionClock.remainingSeconds(session, 2, muchLater)).isEqualTo(3000);
        assertThat(SessionClock.effectiveState(session, 2, muchLater)).isEqualTo(SessionState.PAUSED);

        session.setRunningSince(muchLater);
        session.setState(SessionState.RUNNING);
        OffsetDateTime fiveMoreMinutes = muchLater.plusMinutes(5);
        assertThat(SessionClock.consumedSeconds(session, fiveMoreMinutes)).isEqualTo(900);
        assertThat(SessionClock.remainingSeconds(session, 2, fiveMoreMinutes)).isEqualTo(2700);
    }

    @Test
    void manyPauseResumeCyclesAccumulateExactlyThePlayedTime() {
        Session session = openSession();
        OffsetDateTime at = T0;
        // Four bursts of 7 minutes each, with idle gaps of 20 minutes that must not count.
        for (int burst = 0; burst < 4; burst++) {
            session.setState(SessionState.RUNNING);
            session.setRunningSince(at);
            at = at.plusMinutes(7);
            session.setConsumedSec(SessionClock.bankedSecondsOnStop(session, 4, at));
            session.setRunningSince(null);
            session.setState(SessionState.PAUSED);
            at = at.plusMinutes(20);
        }

        assertThat(session.getConsumedSec()).isEqualTo(4 * 7 * 60);
        assertThat(SessionClock.remainingSeconds(session, 4, at)).isEqualTo(7200 - 1680);
    }

    @Test
    void theCountdownStopsAtZeroAndTheSeatReadsLocked() {
        Session session = running(0, T0);
        OffsetDateTime exhausted = T0.plusMinutes(30);

        assertThat(SessionClock.remainingSeconds(session, 1, exhausted)).isZero();
        assertThat(SessionClock.isOutOfTime(session, 1, exhausted)).isTrue();
        assertThat(SessionClock.effectiveState(session, 1, exhausted)).isEqualTo(SessionState.LOCKED);
    }

    @Test
    void oneSecondBeforeTheEndIsStillRunning() {
        Session session = running(0, T0);
        OffsetDateTime nearlyThere = T0.plusMinutes(30).minusSeconds(1);

        assertThat(SessionClock.remainingSeconds(session, 1, nearlyThere)).isEqualTo(1);
        assertThat(SessionClock.isOutOfTime(session, 1, nearlyThere)).isFalse();
        assertThat(SessionClock.effectiveState(session, 1, nearlyThere)).isEqualTo(SessionState.RUNNING);
    }

    @Test
    void anOverrunNeverRecordsMinutesNobodyPaidFor() {
        Session session = running(0, T0);
        // The sweeper was late by ten minutes; the customer still only bought 30.
        OffsetDateTime late = T0.plusMinutes(40);

        assertThat(SessionClock.consumedSeconds(session, late)).isEqualTo(2400);
        assertThat(SessionClock.bankedSecondsOnStop(session, 1, late)).isEqualTo(1800);
        assertThat(SessionClock.remainingSeconds(session, 1, late)).isZero();
    }

    @Test
    void aPausedSessionThatLosesItsLastBlockIsOutOfTime() {
        // 1800 s banked, two blocks bought, paused. Returning the unplayed block leaves nothing.
        Session session = openSession();
        session.setState(SessionState.PAUSED);
        session.setConsumedSec(1800);

        assertThat(SessionClock.effectiveState(session, 2, T0)).isEqualTo(SessionState.PAUSED);
        assertThat(SessionClock.effectiveState(session, 1, T0)).isEqualTo(SessionState.LOCKED);
    }

    @Test
    void aLockedOrClosedSessionIsNeverReinterpreted() {
        Session locked = openSession();
        locked.setState(SessionState.LOCKED);
        locked.setConsumedSec(1800);
        assertThat(SessionClock.effectiveState(locked, 4, T0)).isEqualTo(SessionState.LOCKED);

        Session closed = openSession();
        closed.setState(SessionState.CLOSED);
        assertThat(SessionClock.effectiveState(closed, 0, T0)).isEqualTo(SessionState.CLOSED);
    }

    @Test
    void aClockThatAppearsToRunBackwardsNeverGivesTimeBack() {
        // Defensive: NTP nudges, a replica read — elapsed can never be negative.
        Session session = running(600, T0);

        assertThat(SessionClock.elapsedSinceResume(session, T0.minusMinutes(5))).isZero();
        assertThat(SessionClock.consumedSeconds(session, T0.minusMinutes(5))).isEqualTo(600);
    }

    @Test
    void elapsedTimeIsMeasuredAcrossOffsetsNotWallClockDigits() {
        // The same instant written in UTC: +06:00 18:00 is 12:00Z. Ten minutes is ten minutes.
        Session session = running(0, T0);
        OffsetDateTime tenMinutesLaterInUtc =
                OffsetDateTime.of(2026, 8, 21, 12, 10, 0, 0, ZoneOffset.UTC);

        assertThat(SessionClock.consumedSeconds(session, tenMinutesLaterInUtc)).isEqualTo(600);
    }

    private static Session openSession() {
        return new Session(1L, 1L);
    }

    private static Session running(int consumedSec, OffsetDateTime since) {
        Session session = openSession();
        session.setState(SessionState.RUNNING);
        session.setConsumedSec(consumedSec);
        session.setRunningSince(since);
        return session;
    }
}
