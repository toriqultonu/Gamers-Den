package dev.gamersden.common.spi;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * The narrow read the {@code session} package needs from {@code station} — which seat it is about
 * to fill, and what one 30-minute block costs on it right now — without reaching for
 * {@code StationRepository} or {@code PricingRepository} (ARCHITECTURE.md §3: no cross-package
 * repository access, call the owning package's service).
 *
 * <p>Implemented by {@code station/domain/StationLookupService}. The price is deliberately asked
 * for <em>at an instant</em>: the morning window is evaluated in venue time and the answer is
 * snapshotted onto {@code session_blocks.price}, so a later rate edit never reaches a sold block.
 */
public interface StationLookup {

    /** The station, or empty when the id is unknown. */
    Optional<StationInfo> find(long stationId);

    /**
     * What one block costs on this station at {@code at} — the morning-window aware half-hour rate
     * the caller snapshots onto the block it is about to insert.
     */
    int blockPriceAt(long stationId, OffsetDateTime at);

    /**
     * @param consoleType {@code PS5|PS4} — a string here so {@code common} stays free of the
     *                    {@code station} package's enum; callers compare, they do not switch
     * @param underMaintenance true while an Admin has taken the seat off the floor
     */
    record StationInfo(long id, String name, String consoleType, boolean underMaintenance) {
    }
}
