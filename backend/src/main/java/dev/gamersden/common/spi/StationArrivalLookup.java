package dev.gamersden.common.spi;

import java.util.Map;

/**
 * The narrow read the {@code station} package needs from {@code booking} — the checked-in customer
 * waiting for a seat, which is the {@code arrival} half of {@code GET /stations}
 * (api-contract.md, "Stations &amp; pricing"; the "Seat #NN «name» · 2 h prepaid" prompt of
 * design.md S3).
 *
 * <p>An arrival is a booking, never a walk-up ticket: a booking names the console it was sold for,
 * so its token can be offered on that card. Walk-up tokens belong to nobody's console and live in
 * the Floor's queue rail instead ({@code GET /play-queue}).
 *
 * <p>Implemented by {@code booking/domain/BookingSeatService}, in one query for the whole grid.
 */
public interface StationArrivalLookup {

    /** The arrival waiting on each station, keyed by station id; stations without one are absent. */
    Map<Long, Arrival> arrivalsByStation();

    /**
     * @param queueEntryId what {@code POST /sessions} is called with to load the prepaid blocks
     * @param tokenNo      the daily sequence the card offers as "Seat #NN"
     * @param blocks       prepaid 30-minute blocks that load when the token is seated
     */
    record Arrival(long queueEntryId, int tokenNo, String name, int blocks, long bookingId) {
    }
}
