package dev.gamersden.station.web;

import dev.gamersden.station.domain.ConsoleType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

import java.time.LocalTime;

/**
 * {@code PUT /pricing} (one entry per console type) and {@code PUT /pricing/{consoleType}} —
 * Admin. Every field optional; omitted fields keep their stored value. Edits reach new blocks
 * only: running sessions keep the prices they bought.
 *
 * @param consoleType required in the bulk body; on {@code /pricing/{consoleType}} it may be
 *                    omitted, and must match the path when present
 */
@Schema(name = "UpdatePricingRequest")
public record UpdatePricingRequest(
        ConsoleType consoleType,
        @Positive Integer perHour,
        @Positive Integer perHalfHour,
        @Min(0) @Max(100) Integer morningDiscountPct,
        @Schema(type = "string", example = "10:00") LocalTime morningStart,
        @Schema(type = "string", example = "14:00") LocalTime morningEnd) {
}
