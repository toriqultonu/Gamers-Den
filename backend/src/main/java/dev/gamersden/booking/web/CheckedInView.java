package dev.gamersden.booking.web;

import dev.gamersden.booking.domain.BookingService;
import dev.gamersden.common.spi.QueueTokenIssuing;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

/**
 * What {@code POST /bookings/{id}/check-in} returns: {@code {token, printJobId}} plus the booking
 * whose row has just moved from Upcoming to History (api-contract.md, Pre-bookings).
 *
 * <p>The token is an object rather than a bare number because the number alone is ambiguous after
 * a day rollover: {@code queueEntryId} is the key everything downstream uses, {@code tokenNo} is
 * what is printed, and {@code tokenDate} says which day's counter it came off (invariant §5.10).
 */
@Schema(name = "BookingCheckedIn", description = "A booking checked in, holding a queue token")
public record CheckedInView(BookingView booking, TokenView token, long printJobId) {

    public static CheckedInView of(BookingService.CheckedIn checkedIn) {
        return new CheckedInView(BookingView.of(checkedIn.booking()),
                TokenView.of(checkedIn.token()), checkedIn.printJobId());
    }

    /** @param tokenNo printed double-height as {@code TOKEN #NN} on the P6 stub */
    @Schema(name = "QueueToken")
    public record TokenView(long queueEntryId, int tokenNo, LocalDate tokenDate) {

        static TokenView of(QueueTokenIssuing.IssuedToken token) {
            return new TokenView(token.queueEntryId(), token.tokenNo(), token.tokenDate());
        }
    }
}
