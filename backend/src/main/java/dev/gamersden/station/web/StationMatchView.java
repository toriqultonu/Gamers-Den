package dev.gamersden.station.web;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The tournament match a station is hosting — "Now on «console»" in design.md S12. Shaped here so
 * the Floor contract is stable, always {@code null} until B13 brings the bracket and its match
 * timers; a console held by a station block already reads {@code RESERVED} without one.
 */
@Schema(name = "StationMatch", description = "Stubbed until B13 (bracket) — always null for now")
public record StationMatchView(
        long tournamentId,
        long matchId,
        String tournamentName,
        long remainingSeconds) {
}
