package dev.gamersden.station.web;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The checked-in arrival waiting for this seat — the "Seat #NN «name» · 2 h prepaid" prompt in
 * design.md S3. Shaped here so the Floor contract is stable, always {@code null} until B16 fills
 * it in from the play queue; a station with one waiting reads {@code BOOKED}.
 */
@Schema(name = "StationArrival", description = "Stubbed until B16 (play queue) — always null for now")
public record StationArrivalView(
        long queueEntryId,
        int token,
        String name,
        @Schema(description = "Prepaid 30-minute blocks that load when the token is seated") int blocks) {
}
