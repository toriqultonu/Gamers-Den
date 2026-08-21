package dev.gamersden.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Swaps the venue {@link java.time.Clock} for one a test can move. Everything server-side reads
 * time through that bean (ARCHITECTURE.md §5.1), so a suite can park the application at 13:59 in
 * Dhaka or push a session past its last block without waiting for real minutes to pass.
 *
 * <p>Imported rather than nested so every suite that needs it shares one Spring context.
 */
@TestConfiguration
public class MutableClockConfig {

    @Bean
    @Primary
    public MutableClock testClock() {
        return new MutableClock();
    }
}
