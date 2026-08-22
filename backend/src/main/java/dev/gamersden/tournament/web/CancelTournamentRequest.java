package dev.gamersden.tournament.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /tournaments/{id}/cancel} — Manager+. The reason is optional but recorded on
 * {@code tournaments.cancelled_reason} and on every refund it triggers.
 */
@Schema(name = "CancelTournamentRequest")
public record CancelTournamentRequest(@Size(max = 200) String reason) {
}
