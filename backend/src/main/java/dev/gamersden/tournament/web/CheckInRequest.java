package dev.gamersden.tournament.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /tournament-entries/{id}/check-in} — the QR scanned off the P5 stub
 * (docs/tournaments.md §7). The token has to match the entry it is presented against.
 */
@Schema(name = "CheckInRequest")
public record CheckInRequest(@NotBlank String qrToken) {
}
