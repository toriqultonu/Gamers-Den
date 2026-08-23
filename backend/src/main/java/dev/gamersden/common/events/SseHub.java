package dev.gamersden.common.events;

import dev.gamersden.common.config.GamersDenProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * The SSE hub of ARCHITECTURE.md §4.5 — every terminal that is holding {@code GET /events} open,
 * and the one place a payload is written to all of them.
 *
 * <p>Three decisions worth knowing about.
 *
 * <p><strong>Sending is synchronous, on the thread that made the change.</strong> A POS request
 * therefore returns only once the floor has been told, which is what makes the whole feature
 * testable and what makes "the seat is taken" arrive before the response that took it. The venue
 * runs a handful of terminals and the payloads are single cards, so the cost is a few buffered
 * writes; this is not a fan-out for the open internet.
 *
 * <p><strong>A broken subscriber is dropped, never propagated.</strong> A closed browser tab must
 * not fail the sale that tried to notify it, so every send is caught here and the emitter removed.
 * The frontend reconnects on its own, and polls every 10 s meanwhile (§4.5), so a dropped stream
 * costs freshness and nothing else.
 *
 * <p><strong>Nothing is assembled when nobody is listening.</strong> {@link #publish(LiveEvent,
 * Supplier)} takes a supplier so the emitters' listeners can skip the read that builds the GET
 * shape entirely on a venue with no screen open.
 */
@Component
public class SseHub {

    private static final Logger log = LoggerFactory.getLogger(SseHub.class);

    /**
     * What the browser is told to wait before reconnecting. Shorter than the polling fallback, so
     * a reconnect normally beats the next poll to the data.
     */
    private static final long RECONNECT_MILLIS = 3_000L;

    private final Set<Subscriber> subscribers = ConcurrentHashMap.newKeySet();
    private final AtomicLong sequence = new AtomicLong();
    private final long timeoutMillis;

    public SseHub(GamersDenProperties properties) {
        this.timeoutMillis = properties.events().timeout().toMillis();
    }

    // ---- GET /events ---------------------------------------------------------------------------

    /**
     * Registers one terminal and hands back the emitter the controller returns.
     *
     * <p>The stream is closed by the server after {@code gamersden.events.timeout} rather than
     * being held forever: an emitter that outlives its client is a leak, and a reconnect is free.
     * The opening comment is sent immediately so the client knows it is connected before anything
     * happens on the floor.
     */
    public SseEmitter subscribe(String who) {
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        Subscriber subscriber = new Subscriber(sequence.incrementAndGet(), who, emitter);
        subscribers.add(subscriber);
        emitter.onCompletion(() -> subscribers.remove(subscriber));
        emitter.onTimeout(() -> {
            subscribers.remove(subscriber);
            emitter.complete();
        });
        emitter.onError(error -> subscribers.remove(subscriber));

        if (send(subscriber, SseEmitter.event().comment("connected").reconnectTime(RECONNECT_MILLIS))) {
            log.info("events: {} subscribed ({} open)", subscriber, subscribers.size());
        }
        return emitter;
    }

    // ---- publishing ----------------------------------------------------------------------------

    /** Writes one event to every open stream. */
    public void publish(LiveEvent event, Object payload) {
        if (subscribers.isEmpty() || payload == null) {
            return;
        }
        SseEmitter.SseEventBuilder built = SseEmitter.event()
                .name(event.wireName())
                .data(payload, MediaType.APPLICATION_JSON);
        int delivered = 0;
        for (Subscriber subscriber : subscribers) {
            if (send(subscriber, built)) {
                delivered++;
            }
        }
        log.debug("events: {} delivered to {} subscriber(s)", event.wireName(), delivered);
    }

    /** The same, with the payload built only if there is somebody to send it to. */
    public void publish(LiveEvent event, Supplier<?> payload) {
        if (subscribers.isEmpty()) {
            return;
        }
        publish(event, payload.get());
    }

    public boolean hasSubscribers() {
        return !subscribers.isEmpty();
    }

    public int subscriberCount() {
        return subscribers.size();
    }

    /**
     * A comment down every open stream on a timer. It carries no event, so no client handler runs;
     * it exists so that a proxy — or a terminal that has been unplugged — is discovered while the
     * venue is quiet rather than at the moment something finally happens.
     */
    @Scheduled(fixedDelayString = "${gamersden.events.heartbeat}")
    public void heartbeat() {
        if (subscribers.isEmpty()) {
            return;
        }
        SseEmitter.SseEventBuilder ping = SseEmitter.event().comment("ping");
        subscribers.forEach(subscriber -> send(subscriber, ping));
    }

    /**
     * Ends every open stream on shutdown.
     *
     * <p>On {@code ContextClosedEvent} rather than {@code @PreDestroy}, because the ordering is
     * the point: the event is published before the web server is stopped, and an SSE subscription
     * is an async request the container would otherwise sit and wait on. A venue box restarting
     * after a settings change should not hold the door for a terminal that will reconnect anyway.
     */
    @EventListener(ContextClosedEvent.class)
    public void closeAll() {
        if (subscribers.isEmpty()) {
            return;
        }
        log.info("events: closing {} open stream(s)", subscribers.size());
        for (Subscriber subscriber : subscribers) {
            subscribers.remove(subscriber);
            try {
                subscriber.emitter().complete();
            } catch (RuntimeException ignored) {
                // Already gone; there is nothing to close and nothing to report.
            }
        }
    }

    /** @return true when the write reached the client; false when the subscriber was dropped */
    private boolean send(Subscriber subscriber, SseEmitter.SseEventBuilder event) {
        try {
            subscriber.emitter().send(event);
            return true;
        } catch (IOException | RuntimeException ex) {
            // A gone client is the normal end of a stream, not an incident: log it at debug and
            // let the sale that was being announced carry on.
            subscribers.remove(subscriber);
            log.debug("events: dropped {} ({})", subscriber, ex.toString());
            try {
                subscriber.emitter().completeWithError(ex);
            } catch (RuntimeException ignored) {
                // Already completed by the container; nothing left to close.
            }
            return false;
        }
    }

    /** One open stream. Identity is the id, so two terminals with the same name stay distinct. */
    private record Subscriber(long id, String who, SseEmitter emitter) {

        @Override
        public boolean equals(Object other) {
            return other instanceof Subscriber subscriber && subscriber.id == id;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(id);
        }

        @Override
        public String toString() {
            return "#" + id + " " + who;
        }
    }
}
