package dev.gamersden.station.domain;

import dev.gamersden.common.spi.MatchLookup;
import dev.gamersden.common.spi.SessionLookup;

/**
 * A station plus everything the Floor needs to draw its card in one read. {@code arrival} is the
 * B16 half of the summary — the shape is fixed here so that task only fills values in.
 *
 * @param reserved true while a running tournament holds this console (docs/tournaments.md §2)
 * @param match    the tournament match being played on it right now, or null — a reserved console
 *                 with a match counts down like a session, one without reads
 *                 "Reserved · tournament" (docs/tournaments.md §4)
 */
public record StationSummary(Station station,
                             StationFloorState floorState,
                             SessionLookup.LiveSession session,
                             boolean reserved,
                             MatchLookup.LiveMatch match) {

    public static StationSummary of(Station station, SessionLookup.LiveSession session,
                                    boolean reserved, MatchLookup.LiveMatch match) {
        return new StationSummary(station, floorStateOf(station, session, reserved), session,
                reserved, match);
    }

    /**
     * A live session always wins the card: a seat is never shown as under maintenance or reserved
     * while somebody is still playing on it (and {@code StationService} refuses to take a busy seat
     * off the floor in the first place). Reservation outranks maintenance-free idleness — an empty
     * blocked console reads "Reserved · tournament" rather than free (docs/tournaments.md §4).
     *
     * <p>A match on a reserved console does not change the state, only what the card draws: the
     * seat is still RESERVED, and the match line beside it is what turns "Reserved · tournament"
     * into a countdown (§4).
     */
    private static StationFloorState floorStateOf(Station station, SessionLookup.LiveSession session,
                                                  boolean reserved) {
        if (session != null) {
            return switch (session.state()) {
                case "OPEN" -> StationFloorState.OPEN;
                case "RUNNING" -> StationFloorState.RUNNING;
                case "PAUSED" -> StationFloorState.PAUSED;
                default -> StationFloorState.LOCKED;
            };
        }
        if (station.getStatus() == StationStatus.MAINTENANCE) {
            return StationFloorState.MAINTENANCE;
        }
        return reserved ? StationFloorState.RESERVED : StationFloorState.FREE;
    }
}
