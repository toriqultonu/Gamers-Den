package dev.gamersden.tournament.web;

import dev.gamersden.tournament.domain.Cadence;
import dev.gamersden.tournament.domain.Tournament;
import dev.gamersden.tournament.domain.TournamentStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * A tournament card (design.md S12). {@code entries} and {@code slotsLeft} are what the POS menu
 * needs to draw the Tournament category — the card is disabled once {@code slotsLeft} hits zero
 * (docs/tournaments.md §5) — and both are counted on every read, never stored (invariant §5.4).
 */
@Schema(name = "Tournament")
public record TournamentView(Long id,
                             String name,
                             String game,
                             Cadence cadence,
                             OffsetDateTime scheduledAt,
                             int entryFee,
                             int prizePool,
                             int maxPlayers,
                             int matchDurationMin,
                             TournamentStatus status,
                             int entries,
                             int slotsLeft,
                             Long winnerEntryId,
                             String cancelledReason,
                             OffsetDateTime createdAt) {

    public static TournamentView of(Tournament tournament, int entries) {
        return new TournamentView(tournament.getId(), tournament.getName(), tournament.getGame(),
                tournament.getCadence(), tournament.getScheduledAt(), tournament.getEntryFee(),
                tournament.getPrizePool(), tournament.getMaxPlayers(),
                tournament.getMatchDurationMin(), tournament.getStatus(), entries,
                Math.max(0, tournament.getMaxPlayers() - entries), tournament.getWinnerEntryId(),
                tournament.getCancelledReason(), tournament.getCreatedAt());
    }
}
