package dev.gamersden.common.config;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * Venue-clock helpers. Every countdown and day boundary in the system derives from these, never
 * from a client clock (ARCHITECTURE.md §5.1, §5.10).
 */
public final class VenueTime {

    public static final ZoneId ZONE = ZoneId.of("Asia/Dhaka");

    private VenueTime() {
    }

    /**
     * Now, in venue time, truncated to microseconds — the precision {@code TIMESTAMPTZ} stores.
     * Postgres rounds a nanosecond value to the nearest microsecond, so an untruncated stamp can
     * come back out of the database up to 500 ns <em>later</em> than it went in; a countdown
     * measured against it would then lose a whole second to truncation. Writing what the column
     * can hold exactly makes a round trip lossless (invariant §5.1).
     */
    public static OffsetDateTime now(Clock clock) {
        return OffsetDateTime.ofInstant(clock.instant(), ZONE).truncatedTo(ChronoUnit.MICROS);
    }

    /** The business day an instant falls in — the key {@code token_seq} counts against. */
    public static LocalDate businessDay(Clock clock) {
        return businessDay(clock.instant());
    }

    public static LocalDate businessDay(Instant instant) {
        return instant.atZone(ZONE).toLocalDate();
    }

    public static OffsetDateTime atZone(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZONE);
    }
}
