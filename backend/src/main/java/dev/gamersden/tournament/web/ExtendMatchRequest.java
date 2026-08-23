package dev.gamersden.tournament.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * {@code POST /tournaments/{id}/matches/{mid}/extend} — how much time to add.
 *
 * <p>Minutes rather than a new deadline, because the button on the match board is "+5 min" and the
 * server owns the clock: a client that sent an absolute end time would be asserting what time it
 * is (docs/tournaments.md §4, invariant §5.1). Repeated presses accumulate.
 */
@Schema(name = "ExtendMatchRequest")
public record ExtendMatchRequest(
        @Schema(description = "Minutes to add on top of whatever has already been added",
                example = "5")
        @NotNull @Min(1) @Max(240) Integer minutes) {
}
