package dev.gamersden.station.web;

import dev.gamersden.common.spi.StationArrivalLookup;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The checked-in arrival waiting for this seat — the "Seat #NN «name» · 2 h prepaid" prompt in
 * design.md S3 (docs/bookings.md §2).
 *
 * <p>Present only on a free console someone has already checked in for; {@code null} otherwise,
 * and null members are omitted from the JSON, so an ordinary card carries no {@code arrival} at
 * all. It does not change the card's {@code floorState}: a customer waiting is not a customer
 * playing.
 *
 * <p>{@code queueEntryId} is what the seat action sends — to {@code POST /play-queue/{id}/seat},
 * or to {@code POST /sessions} as {@code queueEntryId}. The token number is for the operator to
 * read out; the id is what the server acts on, because the number restarts at venue midnight and
 * the id never does (invariant §5.10).
 */
@Schema(name = "StationArrival", description = "A checked-in booking waiting for this console")
public record StationArrivalView(
        long queueEntryId,
        long bookingId,
        int token,
        String name,
        @Schema(description = "Prepaid 30-minute blocks that load when the token is seated") int blocks) {

    /** {@code null} in, {@code null} out — a console nobody is waiting for has no prompt. */
    public static StationArrivalView of(StationArrivalLookup.Arrival arrival) {
        return arrival == null
                ? null
                : new StationArrivalView(arrival.queueEntryId(), arrival.bookingId(),
                        arrival.tokenNo(), arrival.name(), arrival.blocks());
    }
}
