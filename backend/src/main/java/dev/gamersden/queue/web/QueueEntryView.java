package dev.gamersden.queue.web;

import dev.gamersden.queue.domain.QueueEntry;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One row of {@code GET /play-queue} — a QueueRow in the Floor's rail (design.md S3).
 *
 * <p>{@code tokenDate} is on the wire for the case docs/bookings.md §7 calls out: after a day
 * rollover a WAITING token from yesterday keeps working and keeps its place, and the rail shows
 * its issue date so the operator can see why #01 is not today's first customer. {@code id} is what
 * every action uses — seat, refund, {@code POST /sessions} — because the number restarts and the
 * id never does (invariant §5.10).
 *
 * @param source     {@code BOOKING} for a checked-in pre-booking, {@code PLAY_TICKET} for a
 *                   walk-up sold at the counter
 * @param playAmount what the prepaid time was charged at, snapshot at sale — the figure a no-show
 *                   refund hands back
 * @param sessionId  the seat the token was taken to, from SEATED onwards
 */
@Schema(name = "QueueEntry", description = "One issued daily token")
public record QueueEntryView(long id,
                             int tokenNo,
                             LocalDate tokenDate,
                             String source,
                             Long bookingId,
                             long transactionId,
                             String playerName,
                             String consoleType,
                             int blocks,
                             int playAmount,
                             String status,
                             Long sessionId,
                             OffsetDateTime createdAt) {

    public static QueueEntryView of(QueueEntry entry) {
        return new QueueEntryView(entry.getId(), entry.getTokenNo(), entry.getTokenDate(),
                entry.getSource().name(), entry.getBookingId(), entry.getTxId(),
                entry.getPlayerName(), entry.getConsoleType(), entry.getBlocks(),
                entry.getPlayAmount(), entry.getStatus().name(), entry.getSessionId(),
                entry.getCreatedAt());
    }
}
