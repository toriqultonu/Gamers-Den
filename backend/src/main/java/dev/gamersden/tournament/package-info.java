/**
 * Tournaments, entries, bracket, matches, finance, check-in. Owns: tournaments, tournament_station_blocks, tournament_entries, tournament_matches.
 *
 * <p>Layering (ARCHITECTURE.md §3): {@code web/} (controllers + DTOs) → {@code domain/}
 * (entities, services) → {@code repo/} (Spring Data). No cross-package repository access —
 * call the owning package's service.
 */
package dev.gamersden.tournament;
