package dev.gamersden.catalog.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two derived card states (design.md, MenuItemCard). They hang off {@code available} — stock
 * minus what open carts already hold — not off the raw column, so the menu never offers a unit
 * the {@code OUT_OF_STOCK} guard is about to refuse.
 */
class ItemStockTest {

    @Test
    void availableIsStockMinusWhatOpenCartsHold() {
        ItemStock stock = ItemStock.of(item(10, 3), 4);

        assertThat(stock.available()).isEqualTo(6);
        assertThat(stock.isLowStock()).isFalse();
        assertThat(stock.isOutOfStock()).isFalse();
    }

    @Test
    void theReorderMarkIsInclusive() {
        assertThat(ItemStock.of(item(4, 3), 1).isLowStock()).isTrue();
        assertThat(ItemStock.of(item(5, 3), 1).isLowStock()).isFalse();
    }

    @Test
    void aShelfFullOfHeldUnitsCountsAsOutOfStock() {
        ItemStock stock = ItemStock.of(item(2, 0), 2);

        assertThat(stock.available()).isZero();
        assertThat(stock.isOutOfStock()).isTrue();
        assertThat(stock.isLowStock()).isTrue();
    }

    @Test
    void aZeroReorderMarkOnlyTripsWhenTheShelfIsEmpty() {
        assertThat(ItemStock.of(item(1, 0), 0).isLowStock()).isFalse();
        assertThat(ItemStock.of(item(0, 0), 0).isLowStock()).isTrue();
    }

    private static Item item(int stock, int reorderAt) {
        Item item = new Item("Coke", ItemCategory.BEVERAGE, 60);
        item.setStock(stock);
        item.setReorderAt(reorderAt);
        return item;
    }
}
