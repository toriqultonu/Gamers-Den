package dev.gamersden.tournament.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * {@code PUT /tournaments/{id}/blocks} — Manager+. The whole allocation, replaced: sending an
 * empty list releases every console the event was holding.
 */
@Schema(name = "StationBlocksRequest", description = "The consoles this event holds")
public record StationBlocksRequest(@NotNull List<Long> stationIds) {
}
