package dev.gamersden.tournament.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * {@code POST /tournaments/{id}/matches/{mid}/winner} — who won.
 *
 * <p>The entry id rather than a side: the bracket chip the operator taps carries it, and naming
 * the player leaves no room for "A" and "B" to have been redrawn between the read and the tap.
 */
@Schema(name = "RecordWinnerRequest")
public record RecordWinnerRequest(
        @Schema(description = "One of the two entries playing the match")
        @NotNull Long winnerEntryId) {
}
