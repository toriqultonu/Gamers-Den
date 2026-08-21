package dev.gamersden.member.web;

import dev.gamersden.member.domain.MemberVisit;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * One seat the member has taken. {@code playedSeconds} is derived server-side and keeps growing
 * while the visit is live (invariant §5.1); {@code endedAt} is null until the seat closes.
 */
@Schema(name = "MemberVisit", description = "A past or current session the member was attached to")
public record VisitView(long sessionId,
                        long stationId,
                        String stationName,
                        String consoleType,
                        String state,
                        int blocks,
                        long playedSeconds,
                        OffsetDateTime startedAt,
                        OffsetDateTime endedAt) {

    public static VisitView of(MemberVisit visit) {
        return new VisitView(visit.sessionId(), visit.stationId(), visit.stationName(),
                visit.consoleType(), visit.state(), visit.blocks(), visit.playedSeconds(),
                visit.startedAt(), visit.endedAt());
    }
}
