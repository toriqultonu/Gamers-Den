package dev.gamersden.support;

import dev.gamersden.common.config.VenueTime;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

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
}
