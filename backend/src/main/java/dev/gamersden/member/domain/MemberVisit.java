package dev.gamersden.member.domain;

import java.time.OffsetDateTime;

/**
 * One row of the member detail's visits strip (S6): a past or current seat, with the station named
 * rather than left as an id. Assembled from the {@code session} and {@code station} packages'
 * lookups — the {@code member} package reads neither table itself.
 */
public record MemberVisit(long sessionId,
                          long stationId,
                          String stationName,
                          String consoleType,
                          String state,
                          int blocks,
                          long playedSeconds,
                          OffsetDateTime startedAt,
                          OffsetDateTime endedAt) {
}
