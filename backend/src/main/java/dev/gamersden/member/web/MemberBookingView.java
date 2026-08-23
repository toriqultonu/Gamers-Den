package dev.gamersden.member.web;

import dev.gamersden.common.spi.MemberBookingLookup;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * One prepaid slot the member has bought (docs/bookings.md §2). The full row, with its money
 * breakdown and its cancel deadline, lives on {@code GET /bookings}; this is the strip on the
 * member card.
 *
 * @param tokenNo the daily queue token, from check-in onwards; {@code null} while still PAID
 */
@Schema(name = "MemberBooking", description = "A booking the member holds or has held")
public record MemberBookingView(long bookingId,
                                long stationId,
                                String stationName,
                                OffsetDateTime startAt,
                                int blocks,
                                int total,
                                String status,
                                Integer tokenNo) {

    public static MemberBookingView of(MemberBookingLookup.MemberBooking booking) {
        return new MemberBookingView(booking.bookingId(), booking.stationId(),
                booking.stationName(), booking.startAt(), booking.blocks(), booking.total(),
                booking.status(), booking.tokenNo());
    }
}
