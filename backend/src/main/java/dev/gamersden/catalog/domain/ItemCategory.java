package dev.gamersden.catalog.domain;

/**
 * {@code items.category}. These four are the catalogue's whole taxonomy — an enum, not a table.
 * Tournament entries and play tickets are deliberately NOT items: they are first-class payment
 * lines priced from their own tables.
 */
public enum ItemCategory {
    BEVERAGE,
    FOOD,
    SNACK,
    EXTRAS
}
