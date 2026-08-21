package dev.gamersden.catalog.domain;

/**
 * An item as the menu shows it: the stored row plus the two derived numbers a card renders from
 * (design.md, MenuItemCard states "low-stock" and "out-of-stock").
 *
 * <p>{@code available} is {@code stock} minus everything already on open carts. It is what the
 * {@code OUT_OF_STOCK} guard measures against, so the disabled card and the refused line always
 * agree.
 */
public record ItemStock(Item item, int available) {

    public static ItemStock of(Item item, int held) {
        return new ItemStock(item, item.getStock() - held);
    }

    /** At or below the reorder mark — the Overview stock watchlist and the amber card state. */
    public boolean isLowStock() {
        return available <= item.getReorderAt();
    }

    public boolean isOutOfStock() {
        return available <= 0;
    }
}
