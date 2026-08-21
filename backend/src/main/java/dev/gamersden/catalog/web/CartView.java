package dev.gamersden.catalog.web;

import dev.gamersden.catalog.domain.CartSummary;
import dev.gamersden.catalog.domain.CartType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The POS cart panel (design.md S7). {@code type} is derived from {@code sessionId} rather than
 * stored, and {@code total} is summed at read time — the same figure the session's
 * net-outstanding end guard reads through the {@code CartLookup} door.
 *
 * <p>{@code sessionId} is omitted from the JSON on a counter cart
 * ({@code default-property-inclusion: non_null}).
 */
@Schema(name = "Cart")
public record CartView(
        Long id,
        CartType type,
        Long sessionId,
        boolean settled,
        List<CartLineView> lines,
        int total,
        OffsetDateTime createdAt) {

    public static CartView of(CartSummary summary) {
        return new CartView(
                summary.cart().getId(),
                summary.type(),
                summary.cart().getSessionId(),
                summary.cart().isSettled(),
                summary.lines().stream().map(CartLineView::of).toList(),
                summary.total(),
                summary.cart().getCreatedAt());
    }
}
