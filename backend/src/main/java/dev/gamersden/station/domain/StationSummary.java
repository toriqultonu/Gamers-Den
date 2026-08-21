package dev.gamersden.station.domain;

import dev.gamersden.common.spi.SessionLookup;

/**
 * A station plus everything the Floor needs to draw its card in one read. {@code match} and
 * {@code arrival} are the B12 / B16 halves of the summary — the shape is fixed here so those
 * tasks only fill values in.
 */
public record StationSummary(Station station,
                             StationFloorState floorState,
                             SessionLookup.LiveSession session) {

    public static StationSummary of(Station station, SessionLookup.LiveSession session) {
        return new StationSummary(station, floorStateOf(station, session), session);
    }

    /**
     * A live session always wins the card: a seat is never shown as under maintenance while
     * somebody is still playing on it (and {@code StationService} refuses to take a busy seat
     * off the floor in the first place).
     */
    private static StationFloorState floorStateOf(Station station, SessionLookup.LiveSession session) {
        if (session != null) {
            return switch (session.state()) {
                case "OPEN" -> StationFloorState.OPEN;
                case "RUNNING" -> StationFloorState.RUNNING;
                case "PAUSED" -> StationFloorState.PAUSED;
                default -> StationFloorState.LOCKED;
            };
        }
        return station.getStatus() == StationStatus.MAINTENANCE
                ? StationFloorState.MAINTENANCE
                : StationFloorState.FREE;
    }
}
