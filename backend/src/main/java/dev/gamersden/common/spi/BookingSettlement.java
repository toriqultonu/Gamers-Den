package dev.gamersden.common.spi;

/**
 * The narrow write {@code billing} needs from {@code booking} during a booking sale — the row that
 * makes the money a booking (ARCHITECTURE.md §3).
 *
 * <p>The mirror of {@link BookingSale}, and deliberately a different bean on the {@code booking}
 * side: {@code BookingSale} is what {@code booking} calls <em>out</em> to, this is what
 * {@code billing} calls <em>into</em>. Keeping the two directions apart is what stops the two
 * packages from forming a construction cycle — the same shape {@code tournament} uses for entries.
 *
 * <p>Implemented with {@link org.springframework.transaction.annotation.Propagation#MANDATORY}, so
 * there is no code path that can write a booking outside the transaction that took its money;
 * {@code bookings.tx_id} being {@code NOT NULL} makes it unforgeable (invariant §5.7).
 */
public interface BookingSettlement {

    /** Writes the booking the sale just paid for. */
    Registered register(long txId, BookingSale.Order order);

    /**
     * @param overlappingBookingIds live bookings this one runs into on the same console — a
     *                              warning the operator has already overridden by confirming, not
     *                              a refusal (docs/bookings.md §7)
     */
    record Registered(long bookingId, java.util.List<Long> overlappingBookingIds) {

        public Registered {
            overlappingBookingIds = overlappingBookingIds == null
                    ? java.util.List.of()
                    : java.util.List.copyOf(overlappingBookingIds);
        }
    }
}
