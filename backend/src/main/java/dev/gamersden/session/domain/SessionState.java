package dev.gamersden.session.domain;

/** {@code sessions.state} — the floor state machine. */
public enum SessionState {
    OPEN,
    RUNNING,
    PAUSED,
    LOCKED,
    CLOSED
}
