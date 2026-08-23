package dev.gamersden.booking.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

/**
 * {@code POST /bookings} — {@code {stationId, memberId?, name, phone?, startAt, blocks, method,
 * paymentRef?}} (api-contract.md, Pre-bookings).
 *
 * <p>One {@code method} rather than a splits array: a booking is taken at the counter as a single
 * payment, and the contract spells the field that way. A split-tender booking would be a
 * different endpoint's shape, not a wider version of this one.
 *
 * <p>Nothing here carries a price. The play total is quoted from the rate card and the package fee
 * from {@code booking_settings}, both server-side, both snapshotted onto the booking — a client
 * that sent an amount would be asserting what the venue charges (invariant §5.11).
 *
 * @param startAt   the slot, absolute with its offset; it has to be in the future
 * @param blocks    30-minute units to prepay
 * @param method    {@code CASH|BKASH|NAGAD|WALLET}; {@code WALLET} needs {@code memberId}
 * @param paymentRef the bKash/Nagad TrxID — 409 {@code PAYMENT_REF_REQUIRED} without it
 */
@Schema(name = "CreateBookingRequest", description = "Take payment and hold a slot")
public record CreateBookingRequest(@NotNull Long stationId,
                                   Long memberId,
                                   @Size(max = 80) String name,
                                   @Size(max = 32) String phone,
                                   @NotNull OffsetDateTime startAt,
                                   @Min(1) @Max(48) int blocks,
                                   @NotNull String method,
                                   String paymentRef) {
}
