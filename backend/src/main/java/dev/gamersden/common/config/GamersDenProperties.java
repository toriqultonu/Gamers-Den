package dev.gamersden.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.time.LocalTime;

/**
 * Venue-wide knobs under {@code gamersden.*}. Secrets stay in env vars (ARCHITECTURE.md §6);
 * nothing sensitive belongs here.
 *
 * @param timezone        venue timezone; must match {@link VenueTime#ZONE}
 * @param currency        integer-BDT money label
 * @param morningDiscount OPEN FLAG (§8) — documented default 10:00–14:00 at −25%, unconfirmed
 * @param printing        USB printing is on only under the {@code venue} profile
 * @param events          the SSE hub behind {@code GET /events}
 * @param sync            one-way venue → cloud outbox push
 */
@ConfigurationProperties(prefix = "gamersden")
public record GamersDenProperties(
        String timezone,
        String currency,
        MorningDiscount morningDiscount,
        Printing printing,
        Events events,
        Sync sync) {

    public record MorningDiscount(LocalTime from, LocalTime to, int percent) {
    }

    /**
     * The print queue's knobs (B18). {@code enabled} decides whether the real USB transport is
     * wired at all: only the {@code venue} profile owns a printer, so every other profile runs the
     * fake port instead (ARCHITECTURE.md §6, docs/backend-architecture.md §10 "fake PrinterPort in
     * CI").
     *
     * @param enabled       wire the usb4java transport; false → the fake port
     * @param workerEnabled run the background drain trigger; the {@code test} profile switches it
     *                      off and drives {@code PrintQueueWorker} by hand, the same way the
     *                      session lock sweeper is handled, so suites never race the timer
     * @param defaultDevice which discovered printer new jobs go to; blank = the first one found
     * @param pollInterval  how often the trigger looks for queued work
     * @param maxAttempts   automatic transport attempts per claim — 3
     *                      (docs/backend-architecture.md §5)
     * @param retryBackoff  the pause between those attempts — 2 s (same source); shortened under
     *                      {@code test} so a failure path costs milliseconds, not seconds
     * @param usbTimeout    per-transfer libusb timeout on the real device
     */
    public record Printing(boolean enabled,
                           boolean workerEnabled,
                           String defaultDevice,
                           Duration pollInterval,
                           int maxAttempts,
                           Duration retryBackoff,
                           Duration usbTimeout) {
    }

    /**
     * {@code GET /events} (B19). Both numbers are about a stream nobody is reading any more: the
     * server closes one after {@code timeout} rather than holding an emitter for a browser that
     * was closed hours ago, and the heartbeat comment discovers a dead one while the venue is
     * quiet. The frontend reconnects on its own and polls every 10 s meanwhile (ARCHITECTURE.md
     * §4.5), so neither number is load-bearing for correctness.
     *
     * @param timeout   how long one subscription is held open before the client is asked to reconnect
     * @param heartbeat interval of the keep-alive comment down every open stream
     */
    public record Events(Duration timeout, Duration heartbeat) {
    }

    public record Sync(boolean pushEnabled, boolean receiveEnabled, int pushIntervalSeconds) {
    }
}
