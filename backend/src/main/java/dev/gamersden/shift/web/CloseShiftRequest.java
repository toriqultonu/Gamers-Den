package dev.gamersden.shift.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /shifts/current/close}: {@code {countedCash, handoverNote?}}
 * (api-contract.md, "Shifts &amp; expenses").
 *
 * <p>The count is required and never defaulted. A close with no figure would produce a Z whose
 * discrepancy line means nothing, and the discrepancy is the only reason the drawer is counted at
 * all.
 */
@Schema(name = "CloseShiftRequest", description = "Count the drawer and close the shift")
public record CloseShiftRequest(@NotNull @PositiveOrZero Integer countedCash,
                                @Size(max = 500) String handoverNote) {
}
