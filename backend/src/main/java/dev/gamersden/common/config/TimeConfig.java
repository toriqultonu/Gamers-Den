package dev.gamersden.common.config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.TimeZone;

/**
 * Venue timezone is {@code Asia/Dhaka} (ARCHITECTURE.md §6). The JVM default is pinned so day
 * rollover (queue tokens, shifts, reports) is unambiguous; services inject the {@link Clock} bean
 * rather than calling {@code Instant.now()}, which keeps invariant §5.1 (server-side time) testable.
 */
@Configuration
public class TimeConfig {

    @PostConstruct
    void pinDefaultTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone(VenueTime.ZONE));
    }

    @Bean
    public Clock clock() {
        return Clock.system(VenueTime.ZONE);
    }
}
