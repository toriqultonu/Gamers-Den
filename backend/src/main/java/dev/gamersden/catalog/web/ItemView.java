package dev.gamersden.catalog.web;

import dev.gamersden.catalog.domain.ItemCategory;
import dev.gamersden.catalog.domain.ItemStock;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One menu row (design.md, MenuItemCard). {@code stock} is the stored shelf count;
 * {@code available} is that minus everything already held on open carts, and {@code lowStock} /
 * {@code outOfStock} are the card's two disabled-ish states. All three are derived at read time
 * and never stored (invariant: derived values are never stored).
 */
@Schema(name = "Item")
public record ItemView(
        Long id,
        String name,
        ItemCategory category,
        int price,
        int stock,
        int available,
        int reorderAt,
        boolean active,
        boolean lowStock,
        boolean outOfStock) {

    public static ItemView of(ItemStock stock) {
        return new ItemView(
                stock.item().getId(),
                stock.item().getName(),
                stock.item().getCategory(),
                stock.item().getPrice(),
                stock.item().getStock(),
                stock.available(),
                stock.item().getReorderAt(),
                stock.item().isActive(),
                stock.isLowStock(),
                stock.isOutOfStock());
    }
}
