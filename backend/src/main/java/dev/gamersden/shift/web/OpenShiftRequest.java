package dev.gamersden.shift.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * The body of {@code POST /shifts}: {@code {openingFloat}} (api-contract.md, "Shifts &amp;
 * expenses").
 *
 * <p>No terminal field. The shift opens on the till the caller is signed in to — it is a token
 * claim, not something a request may choose, or one terminal could open another's drawer.
 */
@Schema(name = "OpenShiftRequest", description = "Open the caller's terminal for business")
public record OpenShiftRequest(@NotNull @PositiveOrZero Integer openingFloat) {
}
