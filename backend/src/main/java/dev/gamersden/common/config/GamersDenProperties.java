package dev.gamersden.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalTime;

/**
 * Venue-wide knobs under {@code gamersden.*}. Secrets stay in env vars (ARCHITECTURE.md §6);
 * nothing sensitive belongs here.
 *
 * @param timezone        venue timezone; must match {@link VenueTime#ZONE}
 * @param currency        integer-BDT money label
 * @param morningDiscount OPEN FLAG (§8) — documented default 10:00–14:00 at −25%, unconfirmed
 * @param printing        USB printing is on only under the {@code venue} profile
 * @param sync            one-way venue → cloud outbox push
 */
@ConfigurationProperties(prefix = "gamersden")
public record GamersDenProperties(
        String timezone,
        String currency,
        MorningDiscount morningDiscount,
        Printing printing,
        Sync sync) {

    public record MorningDiscount(LocalTime from, LocalTime to, int percent) {
    }

    public record Printing(boolean enabled) {
    }

    public record Sync(boolean pushEnabled, boolean receiveEnabled, int pushIntervalSeconds) {
    }
}
