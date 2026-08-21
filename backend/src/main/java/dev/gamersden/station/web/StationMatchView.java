package dev.gamersden.station.web;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The tournament match a station is hosting — "Now on «console»" in design.md S12. Shaped here so
 * the Floor contract is stable, always {@code null} until B12 fills it in; a station held by a
 * station block reads {@code RESERVED}.
 */
@Schema(name = "StationMatch", description = "Stubbed until B12 (tournaments) — always null for now")
public record StationMatchView(
        long tournamentId,
        long matchId,
        String tournamentName,
        long remainingSeconds) {
}
