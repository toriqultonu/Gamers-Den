package dev.gamersden.catalog.domain;

/**
 * How much of one item a set of settled carts moved, and what it brought in. Not a table — a
 * grouped read behind S9's top-seller table (derived values are never stored, §5.4).
 *
 * <p>Revenue is summed from {@code cart_lines.unit_price}, the snapshot each line was opened at,
 * so a price edit after the sale can never rewrite a report of it.
 */
public record ItemSold(Long itemId, String name, ItemCategory category, Long units, Long revenue) {

    public int unitCount() {
        return units == null ? 0 : Math.toIntExact(units);
    }

    public int revenueAmount() {
        return revenue == null ? 0 : Math.toIntExact(revenue);
    }
}
