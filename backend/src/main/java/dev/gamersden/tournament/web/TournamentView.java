package dev.gamersden.tournament.web;

import dev.gamersden.tournament.domain.Cadence;
import dev.gamersden.tournament.domain.Tournament;
import dev.gamersden.tournament.domain.TournamentEntry;
import dev.gamersden.tournament.domain.TournamentStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * A tournament card (design.md S12). {@code entries} and {@code slotsLeft} are what the POS menu
 * needs to draw the Tournament category — the card is disabled once {@code slotsLeft} hits zero
 * (docs/tournaments.md §5) — and both are counted on every read, never stored (invariant §5.4).
 *
 * <p>{@code winnerName} rides alongside {@code winnerEntryId} so the History tab can list winners
 * and prizes by date without a second read per row (§8).
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
                             @Schema(description = "The champion; null until the final is decided")
                             String winnerName,
                             String cancelledReason,
                             OffsetDateTime createdAt) {

    public static TournamentView of(Tournament tournament, int entries, String winnerName) {
        return new TournamentView(tournament.getId(), tournament.getName(), tournament.getGame(),
                tournament.getCadence(), tournament.getScheduledAt(), tournament.getEntryFee(),
                tournament.getPrizePool(), tournament.getMaxPlayers(),
                tournament.getMatchDurationMin(), tournament.getStatus(), entries,
                Math.max(0, tournament.getMaxPlayers() - entries), tournament.getWinnerEntryId(),
                winnerName, tournament.getCancelledReason(), tournament.getCreatedAt());
    }

    /** When the entries are already in hand, the champion's name comes out of them for free. */
    public static TournamentView of(Tournament tournament, List<TournamentEntry> entries) {
        return of(tournament, entries.size(), nameOf(tournament.getWinnerEntryId(), entries));
    }

    private static String nameOf(Long winnerEntryId, List<TournamentEntry> entries) {
        if (winnerEntryId == null) {
            return null;
        }
        return entries.stream()
                .filter(entry -> winnerEntryId.equals(entry.getId()))
                .map(TournamentEntry::getPlayerName)
                .findFirst().orElse(null);
    }
}
