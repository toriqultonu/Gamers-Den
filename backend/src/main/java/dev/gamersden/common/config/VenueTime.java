package dev.gamersden.common.config;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * Venue-clock helpers. Every countdown and day boundary in the system derives from these, never
 * from a client clock (ARCHITECTURE.md §5.1, §5.10).
 */
public final class VenueTime {

    public static final ZoneId ZONE = ZoneId.of("Asia/Dhaka");

    private VenueTime() {
    }

    public static OffsetDateTime now(Clock clock) {
        return OffsetDateTime.ofInstant(clock.instant(), ZONE);
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
