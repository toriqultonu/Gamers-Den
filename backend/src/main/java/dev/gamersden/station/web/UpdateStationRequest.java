package dev.gamersden.station.web;

import dev.gamersden.station.domain.ConsoleType;
import dev.gamersden.station.domain.StationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** {@code PATCH /stations/{id}} — Admin. Every field optional; omitted fields are left alone. */
@Schema(name = "UpdateStationRequest")
public record UpdateStationRequest(
        @Size(min = 1, max = 40) String name,
        ConsoleType consoleType,
        StationStatus status) {
}
