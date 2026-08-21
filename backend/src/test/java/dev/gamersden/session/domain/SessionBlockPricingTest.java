package dev.gamersden.session.domain;

import dev.gamersden.common.config.VenueTime;
import dev.gamersden.station.domain.ConsoleType;
import dev.gamersden.station.domain.Pricing;
import dev.gamersden.station.domain.PricingService;
import dev.gamersden.station.domain.Station;
import dev.gamersden.station.domain.StationLookupService;
import dev.gamersden.station.repo.PricingRepository;
import dev.gamersden.station.repo.StationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a block costs the moment it is bought — the exact call {@code SessionService} makes before
 * it snapshots the price onto {@code session_blocks.price}.
 *
 * <p>The morning window is half-open, {@code [morningStart, morningEnd)}, so the block sold at
 * 13:59:59 is discounted and the one sold at 14:00:00 is not. It is also evaluated in
 * <strong>venue</strong> time: the same instant expressed in UTC must price identically, or a
 * cloud-profile clock would sell Dhaka mornings at Dhaka evening rates.
 *
 * <p>10:00-14:00 at -25% is the documented default for the morning-discount OPEN FLAG
 * (ARCHITECTURE.md §8) — unconfirmed by the venue, editable through {@code PUT /pricing}.
 */
class SessionBlockPricingTest {

    private static final long STATION_ID = 7L;
    private static final LocalDate DAY = LocalDate.of(2026, 8, 21);

    private Pricing ps5;
    private StationLookupService stations;

    @BeforeEach
    void wireTheRateCard() {
        ps5 = new Pricing(ConsoleType.PS5, 120, 80);
        PricingRepository rates = mock(PricingRepository.class);
        when(rates.findById(ConsoleType.PS5)).thenReturn(Optional.of(ps5));

        StationRepository stationRows = mock(StationRepository.class);
        when(stationRows.findById(STATION_ID)).thenReturn(Optional.of(station()));

        stations = new StationLookupService(stationRows,
                new PricingService(rates, Clock.system(VenueTime.ZONE)));
    }

    @Test
    void theLastSecondOfTheMorningWindowIsStillDiscounted() {
        assertThat(stations.blockPriceAt(STATION_ID, venue(13, 59, 59))).isEqualTo(60);
    }

    @Test
    void theFirstSecondAfterItPaysTheFullRate() {
        assertThat(stations.blockPriceAt(STATION_ID, venue(14, 0, 0))).isEqualTo(80);
    }

    @Test
    void theWindowOpensExactlyOnTheHourAndNotASecondSooner() {
        assertThat(stations.blockPriceAt(STATION_ID, venue(9, 59, 59))).isEqualTo(80);
        assertThat(stations.blockPriceAt(STATION_ID, venue(10, 0, 0))).isEqualTo(60);
    }

    @Test
    void aBlockAtMiddayIsDiscountedAndOneInTheEveningIsNot() {
        assertThat(stations.blockPriceAt(STATION_ID, venue(12, 0, 0))).isEqualTo(60);
        assertThat(stations.blockPriceAt(STATION_ID, venue(20, 30, 0))).isEqualTo(80);
    }

    @Test
    void theWindowIsReadInVenueTimeNotInWhateverOffsetTheCallerBrings() {
        // 13:59:59 in Dhaka is 07:59:59Z — outside the window by wall-clock digits, inside it by
        // instant. Pricing must follow the instant.
        OffsetDateTime sameInstantInUtc =
                venue(13, 59, 59).withOffsetSameInstant(ZoneOffset.UTC);
        assertThat(sameInstantInUtc.getHour()).isEqualTo(7);

        assertThat(stations.blockPriceAt(STATION_ID, sameInstantInUtc)).isEqualTo(60);
    }

    @Test
    void movingTheWindowMovesOnlyTheNextBlock() {
        // The venue confirms a shorter morning: the 13:30 block is now full price. Blocks already
        // sold are untouched — nothing here can reach session_blocks.price.
        ps5.setMorningEnd(LocalTime.of(13, 0));

        assertThat(stations.blockPriceAt(STATION_ID, venue(12, 59, 59))).isEqualTo(60);
        assertThat(stations.blockPriceAt(STATION_ID, venue(13, 30, 0))).isEqualTo(80);
    }

    @Test
    void aZeroPercentDiscountKeepsTheWindowButChargesTheStandardRate() {
        ps5.setMorningDiscountPct(0);

        assertThat(stations.blockPriceAt(STATION_ID, venue(11, 0, 0))).isEqualTo(80);
    }

    @Test
    void theStationCardCarriesTheConsoleTypeTheSeatGuardsCompareAgainst() {
        assertThat(stations.find(STATION_ID)).get()
                .satisfies(info -> {
                    assertThat(info.consoleType()).isEqualTo("PS5");
                    assertThat(info.name()).isEqualTo("PS5-01");
                    assertThat(info.underMaintenance()).isFalse();
                });
    }

    /** A detached entity: {@code id} is normally handed out by the sequence on insert. */
    private static Station station() {
        Station station = new Station("PS5-01", ConsoleType.PS5);
        ReflectionTestUtils.setField(station, "id", STATION_ID);
        return station;
    }

    private static OffsetDateTime venue(int hour, int minute, int second) {
        return ZonedDateTime.of(DAY, LocalTime.of(hour, minute, second), VenueTime.ZONE)
                .toOffsetDateTime();
    }
}
