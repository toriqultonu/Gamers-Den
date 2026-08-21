package dev.gamersden.common.config;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day rollover decides which day a queue token and a shift belong to (§5.10) — it must follow
 * Dhaka midnight (UTC+06:00), not UTC.
 */
class VenueTimeTest {

    @Test
    void businessDayFollowsDhakaMidnightNotUtc() {
        // 18:30 UTC is 00:30 the next day in Dhaka.
        Instant justAfterDhakaMidnight = Instant.parse("2026-08-16T18:30:00Z");

        assertThat(VenueTime.businessDay(justAfterDhakaMidnight)).isEqualTo(LocalDate.of(2026, 8, 17));
    }

    @Test
    void businessDayJustBeforeRolloverStaysOnTheSameDay() {
        Instant justBeforeDhakaMidnight = Instant.parse("2026-08-16T17:59:59Z");

        assertThat(VenueTime.businessDay(justBeforeDhakaMidnight)).isEqualTo(LocalDate.of(2026, 8, 16));
    }

    @Test
    void nowIsTruncatedToWhatTimestamptzCanHoldExactly() {
        // Postgres rounds to the nearest microsecond. A stamp carrying nanoseconds could come back
        // out of the column later than it went in, and a countdown measured against it would lose
        // a whole second to truncation.
        Clock nanos = Clock.fixed(Instant.parse("2026-08-16T04:00:00.123456789Z"), ZoneOffset.UTC);

        OffsetDateTime now = VenueTime.now(nanos);

        assertThat(now.getNano()).isEqualTo(123_456_000);
        assertThat(now).isEqualTo(now.truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    void nowRendersWithTheVenueOffset() {
        Clock fixed = Clock.fixed(Instant.parse("2026-08-16T04:00:00Z"), ZoneOffset.UTC);

        assertThat(VenueTime.now(fixed).toString()).isEqualTo("2026-08-16T10:00+06:00");
        assertThat(VenueTime.businessDay(fixed)).isEqualTo(LocalDate.of(2026, 8, 16));
    }
}
