package dev.gamersden.session.web;

import dev.gamersden.session.domain.SessionDetail;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * One session on the wire. Every duration and every amount here is <strong>derived</strong> from
 * {@code consumed_sec} / {@code running_since} / {@code session_blocks} at {@code serverTime} —
 * nothing in this record is stored (invariants §5.1, §5.4).
 *
 * <p>{@code serverTime} ships with the payload so a client can keep a countdown ticking between
 * polls by measuring against it, never against its own clock.
 *
 * @param state            the effective state; a countdown that hit zero already reads LOCKED
 * @param blocks           non-removed 30-minute blocks bought so far
 * @param paidBlocks       the subset carrying a {@code paid_tx_id} — settled mid-session or prepaid
 * @param remainingSeconds what the Floor counts down; 0 on a LOCKED seat
 * @param netOutstanding   {@code gamingDue + fnbDue} — the end guard, 0 means the seat can close
 */
@Schema(name = "Session", description = "A floor session with its server-derived clock and balance")
public record SessionView(long id,
                          long stationId,
                          Long memberId,
                          long shiftId,
                          Long queueEntryId,
                          String state,
                          int blocks,
                          int paidBlocks,
                          int unpaidBlocks,
                          long purchasedSeconds,
                          long consumedSeconds,
                          long remainingSeconds,
                          int gamingDue,
                          int fnbDue,
                          int netOutstanding,
                          OffsetDateTime startedAt,
                          OffsetDateTime endedAt,
                          OffsetDateTime serverTime) {

    public static SessionView of(SessionDetail detail) {
        return new SessionView(
                detail.session().getId(),
                detail.session().getStationId(),
                detail.session().getMemberId(),
                detail.session().getShiftId(),
                detail.session().getQueueEntryId(),
                detail.state().name(),
                detail.blockCount(),
                detail.paidBlocks(),
                detail.unpaidBlocks(),
                detail.purchasedSeconds(),
                detail.consumedSeconds(),
                detail.remainingSeconds(),
                detail.balance().gamingDue(),
                detail.balance().fnbDue(),
                detail.balance().netOutstanding(),
                detail.session().getStartedAt(),
                detail.session().getEndedAt(),
                detail.serverTime());
    }
}
