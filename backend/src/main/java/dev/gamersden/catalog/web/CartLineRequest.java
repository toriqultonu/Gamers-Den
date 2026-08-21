package dev.gamersden.catalog.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * {@code PUT /carts/{id}/lines} — {@code {itemId, qty}}, where {@code qty: 0} removes the line
 * (api-contract.md, "Cart &amp; menu"). The verb is PUT because the line is set, not incremented:
 * a retried request lands on the same quantity.
 */
@Schema(name = "CartLineRequest")
public record CartLineRequest(
        @NotNull Long itemId,
        @NotNull @PositiveOrZero Integer qty) {
}
