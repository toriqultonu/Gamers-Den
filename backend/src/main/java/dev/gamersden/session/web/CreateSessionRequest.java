package dev.gamersden.session.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * {@code POST /sessions} — {@code {stationId, memberId?, bookingId?|queueEntryId?}}
 * (api-contract.md, Sessions).
 *
 * <p>{@code bookingId} and {@code queueEntryId} are the prepaid-seat handles: at most one, and it
 * loads that token's already-paid blocks onto the new session. Both resolve through the
 * {@code queue} package, which only starts answering with B15/B16 — until then a token id is a
 * 404.
 */
public record CreateSessionRequest(
        @NotNull Long stationId,
        @Schema(description = "Attach a member to the seat for points and wallet at settle")
        Long memberId,
        @Schema(description = "Seat a checked-in booking — loads its prepaid blocks as paid")
        Long bookingId,
        @Schema(description = "Seat a play-queue token — loads its prepaid blocks as paid")
        Long queueEntryId) {
}
