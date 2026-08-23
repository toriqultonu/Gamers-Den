package dev.gamersden.station.domain;

import dev.gamersden.common.error.NotFoundException;
import dev.gamersden.common.error.ValidationFailedException;
import dev.gamersden.common.spi.StationLookup;
import dev.gamersden.station.repo.StationRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * The {@code station} package's answer to {@link StationLookup} — the only door {@code session}
 * uses into {@code stations} and {@code pricing}.
 *
 * <p>{@link #blockPriceAt} is the single place a block price is quoted from: it reads the live
 * rate card through {@link PricingService}, which applies the morning window in venue time. The
 * caller snapshots the answer, so a {@code PUT /pricing} between two blocks moves the second one
 * only.
 *
 * <p>{@link #blockPriceOf} is the same quote asked without a station, for the one thing sold to a
 * console <em>type</em>: a walk-up play ticket, which is on sale precisely because no station of
 * that type is free (docs/bookings.md §3).
 */
@Service
public class StationLookupService implements StationLookup {

    private final StationRepository stations;
    private final PricingService pricing;

    public StationLookupService(StationRepository stations, PricingService pricing) {
        this.stations = stations;
        this.pricing = pricing;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StationInfo> find(long stationId) {
        return stations.findById(stationId).map(StationLookupService::info);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StationInfo> all() {
        return stations.findAll(Sort.by(Sort.Direction.ASC, "name")).stream()
                .map(StationLookupService::info)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public int blockPriceAt(long stationId, OffsetDateTime at) {
        Station station = stations.findById(stationId)
                .orElseThrow(() -> new NotFoundException("Station", stationId));
        return pricing.blockPrice(station.getConsoleType(), at);
    }

    @Override
    @Transactional(readOnly = true)
    public int blockPriceOf(String consoleType, OffsetDateTime at) {
        return pricing.blockPrice(parse(consoleType), at);
    }

    @Override
    @Transactional(readOnly = true)
    public int hourlyRate(long stationId) {
        Station station = stations.findById(stationId)
                .orElseThrow(() -> new NotFoundException("Station", stationId));
        return pricing.get(station.getConsoleType()).getPerHour();
    }

    /**
     * 400 rather than a 500 off {@code valueOf}: the console type arrives as free text on the wire
     * (a {@code playTickets[]} line), so an unknown one is the caller's mistake to be told about.
     */
    private static ConsoleType parse(String consoleType) {
        if (consoleType != null) {
            String trimmed = consoleType.trim();
            for (ConsoleType known : ConsoleType.values()) {
                if (known.name().equalsIgnoreCase(trimmed)) {
                    return known;
                }
            }
        }
        throw ValidationFailedException.onField("consoleType",
                "consoleType is one of %s".formatted(
                        java.util.Arrays.toString(ConsoleType.values())));
    }

    private static StationInfo info(Station station) {
        return new StationInfo(station.getId(), station.getName(), station.getConsoleType().name(),
                station.getStatus() == StationStatus.MAINTENANCE);
    }
}
