package dev.gamersden.tournament.domain;

/**
 * {@code tournaments.status} (docs/tournaments.md §2). The lifecycle is
 * {@code OPEN → LIVE → DONE}, with {@code CANCELLED} reachable from either live state.
 *
 * <p>It is also the only thing that decides whether a station block still holds a console: a seat
 * is reserved iff it is listed for a tournament that is {@link #OPEN} or {@link #LIVE} (§2, the
 * comment under {@code tournament_station_blocks}). Finishing or cancelling therefore releases the
 * consoles by itself — there is no second flag to forget to clear.
 */
public enum TournamentStatus {

    /** Selling entries. Seeds are handed out in sale order. */
    OPEN,

    /** The cap filled or a manager generated the bracket (B13); matches are being played. */
    LIVE,

    /** The final has a winner. */
    DONE,

    /** Called off — every entry refunded, every console released. */
    CANCELLED;

    /** True while the event still holds the consoles it blocked (§2). */
    public boolean holdsStations() {
        return this == OPEN || this == LIVE;
    }

    public boolean isFinished() {
        return this == DONE || this == CANCELLED;
    }
}
