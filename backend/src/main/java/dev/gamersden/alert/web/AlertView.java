package dev.gamersden.alert.web;

import dev.gamersden.alert.domain.Alert;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * One row of {@code GET /alerts} — an AlertCard in the Overview rail (design.md S2), and the
 * payload of the SSE {@code alert} event.
 *
 * <p>{@code type} is the switch the card renders on: {@code CASH_DISCREPANCY} at a shift close,
 * {@code PRINTER_FAILED} when a ticket never made it onto paper, {@code LOW_STOCK} when an item
 * crossed its reorder point. Title and body are written where the alert is raised, by the code
 * that knows the numbers, so nothing here reformats them.
 */
@Schema(name = "Alert", description = "One row of the operator feed")
public record AlertView(long id,
                        String type,
                        String title,
                        String body,
                        boolean read,
                        OffsetDateTime createdAt) {

    public static AlertView of(Alert alert) {
        return new AlertView(alert.getId(), alert.getType(), alert.getTitle(), alert.getBody(),
                alert.isRead(), alert.getCreatedAt());
    }
}
