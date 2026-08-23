package dev.gamersden.queue.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * {@code POST /play-queue/{id}/seat} — {@code {stationId}} (api-contract.md, "Play queue").
 *
 * <p>The console is the operator's choice, not the queue's: staff may seat any waiting token on
 * any free console of the right type (docs/bookings.md §3). The only thing the server insists on
 * is that the type matches — 409 {@code CONSOLE_TYPE_MISMATCH}.
 */
@Schema(name = "SeatQueueEntryRequest", description = "Which console to seat this token on")
public record SeatRequest(@NotNull Long stationId) {
}
