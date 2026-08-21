package dev.gamersden.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled} for the whole app. First user is the idempotency reaper; the print
 * worker and the sync pusher (ARCHITECTURE.md §5.8) join later.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
