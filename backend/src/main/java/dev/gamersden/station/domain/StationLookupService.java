package dev.gamersden.station.domain;

import dev.gamersden.common.error.NotFoundException;
import dev.gamersden.common.spi.StationLookup;
import dev.gamersden.station.repo.StationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * The {@code station} package's answer to {@link StationLookup} — the only door {@code session}
 * uses into {@code stations} and {@code pricing}.
 *
 * <p>{@link #blockPriceAt} is the single place a block price is quoted from: it reads the live
 * rate card through {@link PricingService}, which applies the morning window in venue time. The
 * caller snapshots the answer, so a {@code PUT /pricing} between two blocks moves the second one
 * only.
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
    public int blockPriceAt(long stationId, OffsetDateTime at) {
        Station station = stations.findById(stationId)
                .orElseThrow(() -> new NotFoundException("Station", stationId));
        return pricing.blockPrice(station.getConsoleType(), at);
    }

    private static StationInfo info(Station station) {
        return new StationInfo(station.getId(), station.getName(), station.getConsoleType().name(),
                station.getStatus() == StationStatus.MAINTENANCE);
    }
}
