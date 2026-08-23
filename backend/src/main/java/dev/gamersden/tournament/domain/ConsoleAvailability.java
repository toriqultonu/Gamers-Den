package dev.gamersden.tournament.domain;

/**
 * Why one of an event's allocated consoles can or cannot take the next match — the hint half of
 * the cashier job board (docs/tournaments.md §4).
 *
 * <p>A blocked console is not the same thing as an empty one. The block holds the seat for the
 * event; whether it can host a match <em>right now</em> is a separate question, and the answer is
 * the reason a cashier reads on the board instead of guessing at a 409.
 *
 * @param matchId the match occupying it, when {@link State#MATCH_IN_PLAY}
 */
public record ConsoleAvailability(long stationId, String stationName, State state, Long matchId) {

    public enum State {

        /** Nothing on it — the next match start will take this one. */
        FREE,

        /** A walk-in session is playing on it; the session keeps the seat until it ends (§4). */
        WALK_IN_SESSION,

        /** Already counting down an undecided match — one live match per console (§2). */
        MATCH_IN_PLAY,

        /** An Admin has taken the seat off the floor; it hosts nothing until it comes back. */
        MAINTENANCE
    }

    public boolean isFree() {
        return state == State.FREE;
    }

    /** The line the board shows under the console chip. */
    public String note() {
        return switch (state) {
            case FREE -> "Free";
            case WALK_IN_SESSION -> "Allocated console busy with a walk-in session";
            case MATCH_IN_PLAY -> "Hosting match #" + matchId;
            case MAINTENANCE -> "Under maintenance";
        };
    }
}
