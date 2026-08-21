package dev.gamersden.station.web;

import dev.gamersden.station.domain.ConsoleType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code POST /stations} — Admin. 409 {@code DUPLICATE_NAME} when the name is taken. */
@Schema(name = "CreateStationRequest")
public record CreateStationRequest(
        @NotBlank @Size(max = 40) String name,
        @NotNull ConsoleType consoleType) {
}
