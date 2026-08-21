package dev.gamersden.session.domain;

/** {@code POST /sessions/{id}/clock} — {@code {action: START|PAUSE|RESUME}} (api-contract.md). */
public enum ClockAction {

    /** OPEN → RUNNING. Needs at least one block, else 409 {@code NO_BLOCKS}. */
    START(SessionState.OPEN, SessionState.RUNNING),

    /** RUNNING → PAUSED. Banks the stretch played so far into {@code consumed_sec}. */
    PAUSE(SessionState.RUNNING, SessionState.PAUSED),

    /** PAUSED → RUNNING. Needs time left, else 409 {@code NO_BLOCKS}. */
    RESUME(SessionState.PAUSED, SessionState.RUNNING);

    private final SessionState from;
    private final SessionState to;

    ClockAction(SessionState from, SessionState to) {
        this.from = from;
        this.to = to;
    }

    /** The only state this action may be issued from; anything else is 409 {@code CONFLICT}. */
    public SessionState from() {
        return from;
    }

    public SessionState to() {
        return to;
    }
}
