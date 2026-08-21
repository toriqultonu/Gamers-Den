package dev.gamersden.catalog.web;

import dev.gamersden.catalog.domain.CartSummary;
import dev.gamersden.catalog.domain.ItemCategory;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One cart line. {@code unitPrice} is the snapshot the line was opened at — the item's current
 * price may already differ, and the bill must not move under the customer.
 */
@Schema(name = "CartLine")
public record CartLineView(
        Long itemId,
        String name,
        ItemCategory category,
        int qty,
        int unitPrice,
        int lineTotal) {

    public static CartLineView of(CartSummary.Line line) {
        return new CartLineView(line.itemId(), line.name(), line.category(), line.qty(),
                line.unitPrice(), line.lineTotal());
    }
}
