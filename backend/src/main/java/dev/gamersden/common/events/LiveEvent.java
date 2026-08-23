package dev.gamersden.common.events;

/**
 * The seven event names of {@code GET /events} (ARCHITECTURE.md §4.5, api-contract.md "Live
 * updates &amp; sync"). The wire name is the SSE {@code event:} field the frontend switches on, so
 * it is spelled here once and nowhere else.
 *
 * <p>Each carries the shape of the GET it mirrors — that is the contract §4.5 states: "payloads
 * must equal the corresponding GET shapes", because the frontend writes them straight into the
 * TanStack Query cache key the same GET fills, and polls the GET every 10 s as a fallback. A
 * payload that differed would make the screen flicker between two truths.
 */
public enum LiveEvent {

    /** {@code GET /stations/{id}} — one Floor card, session or match countdown included. */
    STATION_UPDATE("station-update"),

    /** {@code GET /play-queue} — the whole rail, waiting first then today's seated. */
    QUEUE_UPDATE("queue-update"),

    /** {@code GET /bookings/{id}} — one slot. */
    BOOKING_UPDATE("booking-update"),

    /** {@code GET /tournaments/{id}} — the event with its entries, consoles and bracket. */
    TOURNAMENT_UPDATE("tournament-update"),

    /** One row of {@code GET /alerts} — the newest thing on the operator feed. */
    ALERT("alert"),

    /** {@code GET /printers} — every attached device and its live status. */
    PRINTER_STATUS("printer-status"),

    /** {@code GET /sync/status} — outbox state; emitted by the pusher (B22). */
    SYNC_STATUS("sync-status");

    private final String wireName;

    LiveEvent(String wireName) {
        this.wireName = wireName;
    }

    /** The {@code event:} field on the wire — kebab-case, exactly as the contract spells it. */
    public String wireName() {
        return wireName;
    }
}
