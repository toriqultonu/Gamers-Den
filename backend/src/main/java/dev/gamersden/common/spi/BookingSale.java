package dev.gamersden.common.spi;

import java.time.OffsetDateTime;

/**
 * The narrow write the {@code booking} package needs from {@code billing} — {@code POST /bookings}
 * is pay-first (docs/bookings.md §2), so a booking begins its life as a sale.
 *
 * <p>It is the same settle the POS runs. This door exists so the booking form does not grow a
 * second money path: {@code billing} still writes the transaction, its tenders and the print job
 * in one transaction (invariant §5.3), and {@code booking} still writes its row through
 * {@link BookingSettlement} inside it. All this interface adds is a way to start that from a
 * booking-shaped request.
 *
 * <p>{@link Order} carries the payment method as a string so {@code common} stays free of the
 * {@code billing} enum — the implementation parses it and answers 400 on an unknown one, exactly
 * as bean validation would have. The amounts are already priced by {@code booking}: the play
 * total is a rate-card snapshot and the package fee a settings snapshot, both taken before this
 * call so the money row and the booking row can never disagree about what was charged.
 */
public interface BookingSale {

    /** Takes the money and registers the booking, in one transaction. */
    SoldBooking sell(Order order);

    /**
     * @param stationName what the receipt is headed with
     * @param playAmount  {@code blocks ×} the console's block rate at the moment of sale
     * @param packageFee  the {@code booking_settings} snapshot added to every booking
     * @param cutoffHours the cancellation window snapshot the booking will be judged against
     * @param paymentRef  the bKash/Nagad TrxID; required on those methods, ignored elsewhere
     */
    record Order(long stationId,
                 String stationName,
                 String consoleType,
                 Long memberId,
                 String name,
                 String phone,
                 OffsetDateTime startAt,
                 int blocks,
                 int playAmount,
                 int packageFee,
                 int cutoffHours,
                 String method,
                 String paymentRef) {

        /** What the customer pays: prepaid play time plus the package fee. */
        public int total() {
            return playAmount + packageFee;
        }
    }

    /**
     * Named for what it sold rather than plainly {@code Sold}: {@code billing} implements this
     * interface alongside {@link TournamentEntrySale}, and two inherited nested types called
     * {@code Sold} would be ambiguous inside the implementing class.
     *
     * @param printJobId the one job carrying the P1 receipt and the P7 booking confirmation
     */
    record SoldBooking(long transactionId, String publicId, long printJobId, long bookingId,
                       java.util.List<Long> overlappingBookingIds) {

        public SoldBooking {
            overlappingBookingIds = overlappingBookingIds == null
                    ? java.util.List.of()
                    : java.util.List.copyOf(overlappingBookingIds);
        }
    }
}
