package dev.gamersden.station.web;

import dev.gamersden.common.spi.MatchLookup;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The tournament match a station is hosting — "Now on «console»" in design.md S12, and the match
 * countdown on the Floor card (docs/tournaments.md §4).
 *
 * <p>A reserved console with a started match shows this and counts down like a regular session;
 * one without keeps reading "Reserved · tournament". {@code remainingSeconds} is computed from the
 * match's {@code started_at}, its added minutes and the event's match duration on every read — the
 * same server clock the bracket and the match board tick from (invariant §5.1).
 */
@Schema(name = "StationMatch")
public record StationMatchView(
        long tournamentId,
        long matchId,
        String tournamentName,
        @Schema(description = "1 = first round") int round,
        int slot,
        String playerA,
        String playerB,
        long remainingSeconds,
        @Schema(description = "Past zero — the card reads \"match over\" until a winner is recorded")
        boolean timeUp) {

    public static StationMatchView of(MatchLookup.LiveMatch match) {
        if (match == null) {
            return null;
        }
        return new StationMatchView(match.tournamentId(), match.matchId(), match.tournamentName(),
                match.round(), match.slot(), match.playerA(), match.playerB(),
                match.remainingSeconds(), match.timeUp());
    }
}
