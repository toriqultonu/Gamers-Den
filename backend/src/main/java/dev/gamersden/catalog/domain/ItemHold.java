package dev.gamersden.catalog.domain;

/**
 * How many units of one item are sitting on open (unsettled) carts. Not a table — an aggregate
 * read, because {@code items.stock} only moves at settle and derived values are never stored
 * (ARCHITECTURE.md §5.4).
 */
public record ItemHold(Long itemId, Long qty) {

    public int quantity() {
        return qty == null ? 0 : Math.toIntExact(qty);
    }
}
