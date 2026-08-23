package dev.gamersden.common.web;

import dev.gamersden.common.events.SseHub;
import dev.gamersden.common.security.CurrentStaff;
import dev.gamersden.common.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@code GET /events} — the one live stream every screen hangs off (api-contract.md, "Live updates
 * &amp; sync"; ARCHITECTURE.md §4.5).
 *
 * <p>Every operator subscribes. The Floor, the queue rail and the printer banner are the same
 * facts for all three roles, and the stream carries nothing a role could not already read: the
 * Manager+ shapes — shift takings, tournament finance — are never pushed, they are fetched.
 *
 * <p>Authenticated like any other endpoint, by the bearer header. That rules out the browser's
 * bare {@code EventSource}, which cannot set one, and the frontend uses a fetch-based SSE client
 * for exactly this reason; the alternative — a token in the query string — would print access
 * tokens into every access log.
 */
@RestController
@Tag(name = "Live updates")
public class EventsController {

    private final SseHub hub;

    public EventsController(SseHub hub) {
        this.hub = hub;
    }

    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "Subscribe to live updates",
            description = "Server-sent events: station-update (sessions and match timers), "
                    + "queue-update, booking-update, tournament-update, alert, printer-status and "
                    + "sync-status. Each payload is the shape of the GET it mirrors, so a handler "
                    + "can write it straight into the cache the polling fallback fills. Events are "
                    + "emitted after the transaction that caused them commits, so nothing arrives "
                    + "for work that rolled back. The stream is closed periodically by the server "
                    + "— reconnect, and poll every 10 s meanwhile.")
    public SseEmitter subscribe() {
        return hub.subscribe(CurrentStaff.require().terminal());
    }
}
