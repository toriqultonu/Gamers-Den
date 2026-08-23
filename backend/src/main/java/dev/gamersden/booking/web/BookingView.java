package dev.gamersden.booking.web;

import dev.gamersden.booking.domain.Booking;
import dev.gamersden.booking.domain.BookingSummary;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One row of {@code GET /bookings} and the booking on every booking response.
 *
 * <p>Four of these fields are derived, never stored (invariant §5.4): {@code total} is the two
 * snapshots added up, {@code endAt} is the slot's prepaid length, {@code cancellableUntil} is
 * {@code startAt − cutoffHours} off the booking's own snapshot, and {@code cancellable} is that
 * deadline against the server clock. The frontend renders them; it never recomputes them from its
 * own clock (invariant §5.1).
 *
 * @param tokenNo     the daily queue token, from check-in onwards; {@code null} while PAID
 * @param overlapping another live booking shares this console and this time — the warning of
 *                    docs/bookings.md §7, flagged on the Upcoming list
 */
@Schema(name = "Booking", description = "A prepaid slot")
public record BookingView(long id,
                          long stationId,
                          String stationName,
                          String consoleType,
                          Long memberId,
                          String name,
                          String phone,
                          OffsetDateTime startAt,
                          OffsetDateTime endAt,
                          int blocks,
                          int playAmount,
                          int packageFee,
                          int total,
                          int cutoffHours,
                          OffsetDateTime cancellableUntil,
                          boolean cancellable,
                          String status,
                          long transactionId,
                          Long refundTransactionId,
                          Long queueEntryId,
                          Integer tokenNo,
                          LocalDate tokenDate,
                          boolean overlapping,
                          OffsetDateTime createdAt) {

    public static BookingView of(BookingSummary summary) {
        Booking booking = summary.booking();
        return new BookingView(booking.getId(), booking.getStationId(), summary.stationName(),
                summary.consoleType(), booking.getMemberId(), booking.getName(), booking.getPhone(),
                booking.getStartAt(), booking.endAt(), booking.getBlocks(), booking.getPlayAmount(),
                booking.getPackageFee(), booking.total(), booking.getCutoffHours(),
                booking.cancellableUntil(), summary.cancellable(), booking.getStatus().name(),
                booking.getTxId(), booking.getRefundTxId(), booking.getQueueEntryId(),
                summary.tokenNo(), summary.tokenDate(), summary.overlapping(),
                booking.getCreatedAt());
    }
}
