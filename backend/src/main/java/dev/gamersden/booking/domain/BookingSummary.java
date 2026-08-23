package dev.gamersden.booking.domain;

import java.time.LocalDate;

/**
 * One row of {@code GET /bookings} — the booking plus the three things it takes another package
 * to answer: which console it is on, which token it is holding, and whether the operator should
 * be looking at it twice.
 *
 * <p>Everything here except the {@link Booking} itself is assembled at read time and nothing is
 * stored (invariant §5.4): {@code cancellable} is the cutoff arithmetic against the server clock,
 * {@code overlapping} is the docs/bookings.md §7 warning re-evaluated over the list being
 * returned, and the token comes from {@code queue}.
 *
 * @param tokenNo {@code null} until the booking has been checked in
 */
public record BookingSummary(Booking booking,
                             String stationName,
                             String consoleType,
                             Integer tokenNo,
                             LocalDate tokenDate,
                             boolean overlapping,
                             boolean cancellable) {
}
