package dev.gamersden.station.domain;

import dev.gamersden.common.spi.SessionLookup;

/**
 * A station plus everything the Floor needs to draw its card in one read. {@code arrival} is the
 * B16 half of the summary — the shape is fixed here so that task only fills values in.
 *
 * @param reserved true while a running tournament holds this console (docs/tournaments.md §2); the
 *                 match now being played on it, and its countdown, arrive with B13
 */
public record StationSummary(Station station,
                             StationFloorState floorState,
                             SessionLookup.LiveSession session,
                             boolean reserved) {

    public static StationSummary of(Station station, SessionLookup.LiveSession session,
                                    boolean reserved) {
        return new StationSummary(station, floorStateOf(station, session, reserved), session,
                reserved);
    }

    /**
     * A live session always wins the card: a seat is never shown as under maintenance or reserved
     * while somebody is still playing on it (and {@code StationService} refuses to take a busy seat
     * off the floor in the first place). Reservation outranks maintenance-free idleness — an empty
     * blocked console reads "Reserved · tournament" rather than free (docs/tournaments.md §4).
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
