package dev.gamersden.support;

import dev.gamersden.common.config.VenueTime;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * A venue clock a test can push forward. Everything server-side reads time through the
 * {@link Clock} bean (ARCHITECTURE.md §5.1), so shifting this shifts the whole application's idea
 * of "now" — which is how the 48 h idempotency window is tested without waiting two days.
 */
public class MutableClock extends Clock {

    private final ZoneId zone;
    private Instant instant;

    public MutableClock() {
        this(Instant.now(), VenueTime.ZONE);
    }

    private MutableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId other) {
        return new MutableClock(instant, other);
    }

    @Override
    public Instant instant() {
        return instant;
    }

    public void advance(Duration amount) {
        instant = instant.plus(amount);
    }

    public void resetToNow() {
        instant = Instant.now();
    }

    /** Parks the whole application on a chosen wall-clock moment — how the morning window is tested. */
    public void setTo(Instant moment) {
        instant = moment;
    }

    /** The same, expressed in venue-local terms: "today at 13:59:59 in Dhaka". */
    public void setToVenueTime(LocalDate day, LocalTime timeOfDay) {
        instant = ZonedDateTime.of(day, timeOfDay, VenueTime.ZONE).toInstant();
    }
}
