package dev.gamersden.sync.web;

import dev.gamersden.common.security.Roles;
import dev.gamersden.sync.domain.SyncOutboxService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /sync/status} — what the sync chip on every screen reads (design.md §4).
 *
 * <p>Any signed-in operator: the chip is on the shell, and a cashier who cannot see that the venue
 * is running offline is a cashier who cannot tell the owner. The numbers behind the ops stay in
 * the Manager+ report endpoints; this says only how far behind the mirror is.
 */
@RestController
@RequestMapping("/sync")
@Tag(name = "Sync")
public class SyncController {

    private final SyncOutboxService outbox;

    public SyncController(SyncOutboxService outbox) {
        this.outbox = outbox;
    }

    @GetMapping("/status")
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "Outbox state",
            description = "SYNCED when nothing is pending, SYNCING when the venue is ahead of the "
                    + "cloud, OFFLINE when the last push attempt failed. The venue trades either "
                    + "way — the outbox drains on reconnect.")
    public SyncStatusView status() {
        return SyncStatusView.of(outbox.status());
    }
}
