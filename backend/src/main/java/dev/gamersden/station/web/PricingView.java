package dev.gamersden.station.web;

import dev.gamersden.station.domain.ConsoleType;
import dev.gamersden.station.domain.Pricing;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalTime;
import java.time.OffsetDateTime;

/**
 * One console's rate card. {@code perHalfHour} is the block price the Floor sells in ±30-minute
 * steps; {@code perHour} is the headline rate S10 displays. Money is integer BDT.
 */
@Schema(name = "Pricing")
public record PricingView(
        ConsoleType consoleType,
        int perHour,
        int perHalfHour,
        int morningDiscountPct,
        LocalTime morningStart,
        LocalTime morningEnd,
        @Schema(description = "The block price right now — morning discount already applied")
        int currentBlockPrice,
        OffsetDateTime updatedAt) {

    public static PricingView of(Pricing rate, int currentBlockPrice) {
        return new PricingView(rate.getConsoleType(), rate.getPerHour(), rate.getPerHalfHour(),
                rate.getMorningDiscountPct(), rate.getMorningStart(), rate.getMorningEnd(),
                currentBlockPrice, rate.getUpdatedAt());
    }
}
