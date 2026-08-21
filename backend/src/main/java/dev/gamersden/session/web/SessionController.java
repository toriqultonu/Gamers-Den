package dev.gamersden.session.web;

import dev.gamersden.common.security.Roles;
import dev.gamersden.session.domain.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * {@code /sessions} — the Floor's write surface. Every operator seats customers, sells time and
 * runs the clock (api-contract.md §1, permission matrix: "Sessions, POS, payments, prints").
 *
 * <p>{@code POST /sessions/{id}/blocks} is on the idempotency list (§5.2) — the filter rejects it
 * without an {@code Idempotency-Key} and replays the stored response on a retry, so a double tap
 * on the {@code +30} button can never sell two half hours.
 */
@RestController
@RequestMapping("/sessions")
@Tag(name = "Sessions")
public class SessionController {

    private final SessionService sessions;

    public SessionController(SessionService sessions) {
        this.sessions = sessions;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "Seat a customer",
            description = "Opens a session with no time on it. With bookingId or queueEntryId the "
                    + "token's prepaid blocks are loaded as already paid and the token is "
                    + "consumed in the same transaction. 409 STATION_BUSY, STATION_RESERVED, "
                    + "CONSOLE_TYPE_MISMATCH.")
    public SessionView open(@Valid @RequestBody CreateSessionRequest request) {
        return SessionView.of(sessions.open(request.stationId(), request.memberId(),
                request.bookingId(), request.queueEntryId()));
    }

    @PostMapping("/{id}/blocks")
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "Buy or return one 30-minute block",
            description = "Requires an Idempotency-Key. +1 snapshots the current rate (morning "
                    + "window included) onto the new block; -1 returns the newest block that is "
                    + "neither paid for nor played, else 409 BLOCKS_CONSUMED.")
    public SessionView blocks(@PathVariable Long id, @Valid @RequestBody BlocksRequest request) {
        return SessionView.of(sessions.changeBlocks(id, request.delta()));
    }

    @PostMapping("/{id}/clock")
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "Start, pause or resume the clock",
            description = "START from OPEN, PAUSE from RUNNING, RESUME from PAUSED — anything "
                    + "else is 409 CONFLICT. Starting or resuming with no time left is 409 "
                    + "NO_BLOCKS.")
    public SessionView clock(@PathVariable Long id, @Valid @RequestBody ClockRequest request) {
        return SessionView.of(sessions.clock(id, request.action()));
    }

    @PostMapping("/{id}/end")
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "End the session",
            description = "409 SESSION_HAS_BALANCE while net outstanding — unpaid blocks plus the "
                    + "unsettled cart — is above zero. Prepaid blocks count as settled.")
    public SessionView end(@PathVariable Long id) {
        return SessionView.of(sessions.end(id));
    }

    @GetMapping("/{id}")
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "One session with its server-derived clock and balance")
    public SessionView get(@PathVariable Long id) {
        return SessionView.of(sessions.detail(id));
    }

    @GetMapping
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "Sessions on the floor",
            description = "active=true (the default) lists live seats; active=false lists closed "
                    + "sessions, most recently ended first.")
    public List<SessionView> list(@RequestParam(defaultValue = "true") boolean active) {
        return sessions.list(active).stream().map(SessionView::of).toList();
    }
}
