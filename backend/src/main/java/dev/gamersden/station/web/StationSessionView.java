package dev.gamersden.station.web;

import dev.gamersden.common.spi.SessionLookup;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * The live-session line on a station card. {@code remainingSeconds} is server-computed; the Floor
 * ticks it against the server-time offset and never against the local clock (invariant: server
 * time only). Full session reads land with B06's {@code GET /sessions/{id}}.
 */
@Schema(name = "StationSession")
public record StationSessionView(
        long id,
        @Schema(allowableValues = {"OPEN", "RUNNING", "PAUSED", "LOCKED"}) String state,
        Long memberId,
        @Schema(description = "Non-removed 30-minute blocks bought so far") int blocks,
        @Schema(description = "Blocks already paid — prepaid or settled") int paidBlocks,
        long remainingSeconds,
        OffsetDateTime startedAt) {

    public static StationSessionView of(SessionLookup.LiveSession session) {
        return session == null ? null : new StationSessionView(session.id(), session.state(),
                session.memberId(), session.blocks(), session.paidBlocks(),
                session.remainingSeconds(), session.startedAt());
    }
}
