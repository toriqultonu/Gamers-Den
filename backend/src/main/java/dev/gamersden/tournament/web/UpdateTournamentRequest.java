package dev.gamersden.tournament.web;

import dev.gamersden.tournament.domain.Cadence;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

/**
 * {@code PATCH /tournaments/{id}} — Manager+, every field optional.
 *
 * <p>Only an event that is still OPEN can be reconfigured, and two fields are frozen the moment
 * the first ticket is sold: the entry fee (it is the amount a cancel has to refund) and any cap
 * below the entries already taken.
 */
@Schema(name = "UpdateTournamentRequest")
public record UpdateTournamentRequest(
        @Size(max = 80) String name,
        @Size(max = 80) String game,
        Cadence cadence,
        OffsetDateTime scheduledAt,
        @PositiveOrZero Integer entryFee,
        @PositiveOrZero Integer prizePool,
        @Positive Integer maxPlayers,
        @Positive Integer matchDurationMin) {
}
