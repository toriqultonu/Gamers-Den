package dev.gamersden.tournament.web;

import dev.gamersden.tournament.domain.Cadence;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

/**
 * {@code POST /tournaments} — Manager+ (docs/tournaments.md §1). 409 {@code DUPLICATE_NAME} when
 * the name is taken.
 *
 * <p>{@code maxPlayers} is checked again in the service against {@code {4,8,16,32}} and by a
 * database CHECK. Bean validation only bounds it here; "is it a power of two" is a bracket rule,
 * not a shape rule.
 */
@Schema(name = "CreateTournamentRequest")
public record CreateTournamentRequest(
        @NotBlank @Size(max = 80) String name,
        @NotBlank @Size(max = 80) String game,
        @NotNull Cadence cadence,
        @NotNull OffsetDateTime scheduledAt,
        @NotNull @PositiveOrZero Integer entryFee,
        @NotNull @PositiveOrZero Integer prizePool,
        @NotNull @Positive Integer maxPlayers,
        @NotNull @Positive Integer matchDurationMin) {
}
