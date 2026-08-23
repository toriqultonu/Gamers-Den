package dev.gamersden.station.web;

import dev.gamersden.station.domain.ConsoleType;
import dev.gamersden.station.domain.StationFloorState;
import dev.gamersden.station.domain.StationStatus;
import dev.gamersden.station.domain.StationSummary;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * One Floor card (design.md, StationCard). {@code status} is the stored AVAILABLE/MAINTENANCE
 * flag; {@code floorState} is the derived variant the card actually renders, and is never stored
 * (invariant: derived values are never stored).
 *
 * <p>Null members are omitted from the JSON ({@code default-property-inclusion: non_null}), so a
 * free station carries neither {@code session} nor {@code match} nor {@code arrival}. A console
 * reserved by a tournament carries {@code match} only while one is actually being played on it
 * (docs/tournaments.md §4).
 */
@Schema(name = "Station")
public record StationView(
        Long id,
        String name,
        ConsoleType consoleType,
        StationStatus status,
        StationFloorState floorState,
        StationSessionView session,
        StationMatchView match,
        StationArrivalView arrival,
        OffsetDateTime createdAt) {

    public static StationView of(StationSummary summary) {
        return new StationView(
                summary.station().getId(),
                summary.station().getName(),
                summary.station().getConsoleType(),
                summary.station().getStatus(),
                summary.floorState(),
                StationSessionView.of(summary.session()),
                StationMatchView.of(summary.match()),
                StationArrivalView.of(summary.arrival()),
                summary.station().getCreatedAt());
    }
}
