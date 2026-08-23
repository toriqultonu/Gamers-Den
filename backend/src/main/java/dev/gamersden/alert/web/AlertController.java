package dev.gamersden.alert.web;

import dev.gamersden.alert.domain.AlertService;
import dev.gamersden.common.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * {@code /alerts} — the operator feed: the Overview rail's cards and the bell's unread badge
 * (design.md S2), fed live by the SSE {@code alert} event.
 *
 * <p>Every operator reads it, and that is on purpose. Two of the three kinds are the counter's
 * business the moment they happen — a printer that failed mid-sale and an item that has run down
 * — and an alert a cashier cannot see is an alert nobody acts on until an Admin signs in. The
 * numbers a cashier may not have are not here either way: the discrepancy alert names the shift
 * and the amount that is off, which is the fact, while the takings behind it stay in the
 * Manager+ shift and report endpoints.
 */
@RestController
@RequestMapping("/alerts")
@Tag(name = "Alerts")
public class AlertController {

    private final AlertService alerts;

    public AlertController(AlertService alerts) {
        this.alerts = alerts;
    }

    @GetMapping
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "The operator feed",
            description = "Newest first: cash discrepancies at a shift close, print jobs that "
                    + "gave up, and items that crossed their reorder point. unread=true is what "
                    + "the bell badge counts.")
    public List<AlertView> feed(@RequestParam(defaultValue = "false") boolean unread) {
        return alerts.feed(unread).stream().map(AlertView::of).toList();
    }

    @PostMapping("/{id}/read")
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "Mark one alert read",
            description = "Idempotent by nature — an alert already read stays read, and says so.")
    public AlertView read(@PathVariable Long id) {
        return AlertView.of(alerts.markRead(id));
    }

    @PostMapping("/read-all")
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "Clear the bell",
            description = "Marks every unread alert read and answers with what is left — the same "
                    + "list GET /alerts gives.")
    public List<AlertView> readAll() {
        alerts.markAllRead();
        return alerts.feed(false).stream().map(AlertView::of).toList();
    }
}
