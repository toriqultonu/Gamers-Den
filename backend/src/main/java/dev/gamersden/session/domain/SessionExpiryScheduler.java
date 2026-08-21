package dev.gamersden.session.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps {@code sessions.state} honest on an idle floor: a countdown that reaches zero locks the
 * seat even when nobody is looking at it. Reads already derive the same flip, so this changes what
 * is stored, never what a client sees (invariant §5.1: the server owns the clock).
 *
 * <p>15 s is well inside the SSE/poll cadence the Floor runs at (ARCHITECTURE.md §4.5). The trigger
 * is switched off under the {@code test} profile so suites drive
 * {@link SessionService#lockExhaustedSessions()} by hand instead of racing a background thread;
 * the sweep itself is tested, only the timer is not.
 */
@Component
@ConditionalOnProperty(name = "gamersden.sessions.lock-sweeper-enabled", matchIfMissing = true)
public class SessionExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(SessionExpiryScheduler.class);

    private final SessionService sessions;

    public SessionExpiryScheduler(SessionService sessions) {
        this.sessions = sessions;
    }

    @Scheduled(initialDelayString = "PT15S", fixedDelayString = "PT15S")
    public void lockExhaustedSessions() {
        int locked = sessions.lockExhaustedSessions();
        if (locked > 0) {
            log.info("{} session(s) locked — purchased time exhausted", locked);
        }
    }
}
