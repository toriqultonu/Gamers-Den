package dev.gamersden.queue.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /play-tickets} — the standalone alias for one walk-up ticket
 * (api-contract.md, "Play queue").
 *
 * <p>No {@code stationId} and no amount. A play ticket is sold for a console <em>type</em>
 * precisely because no console of that type is free (docs/bookings.md §3), and the price comes off
 * the rate card server-side; a client sending a figure would be asserting what the venue charges
 * (invariant §5.11).
 *
 * <p>One {@code method} rather than the split panel's rows, exactly as {@code POST /bookings} is
 * shaped: this is the "just a ticket" door. A ticket paid across two methods, or bought alongside
 * a seat's bill or a basket, goes through {@code POST /payments} with {@code playTickets[]} — the
 * same settle either way.
 *
 * @param consoleType {@code PS5|PS4}; the seat it is later taken to has to match (409
 *                    {@code CONSOLE_TYPE_MISMATCH})
 * @param blocks      30-minute units to prepay
 * @param playerName  free text; blank falls back to "Walk-in guest"
 * @param method      {@code CASH|BKASH|NAGAD|WALLET}
 * @param paymentRef  the bKash/Nagad TrxID — 409 {@code PAYMENT_REF_REQUIRED} without it
 */
@Schema(name = "SellPlayTicketRequest", description = "Sell one prepaid play-queue token")
public record SellPlayTicketRequest(@NotNull String consoleType,
                                    @Min(1) @Max(48) int blocks,
                                    @Size(max = 80) String playerName,
                                    @NotNull String method,
                                    String paymentRef) {
}
