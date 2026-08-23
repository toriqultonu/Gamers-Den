package dev.gamersden.common.spi;

import java.util.Collection;
import java.util.List;

/**
 * The narrow read the {@code report} package needs from {@code catalog} — what sold and what is
 * running out — without reaching for {@code CartLineRepository} or {@code ItemRepository}
 * (ARCHITECTURE.md §3).
 *
 * <p>Top sellers is deliberately a two-step: {@code billing} says which carts a real sale settled
 * in the window ({@link RevenueLookup#settledCartIds}), and this door prices those carts out of
 * the lines it owns. Neither package reads the other's tables, and the money side stays the one
 * place that decides what "a real sale" means.
 */
public interface SalesItemLookup {

    /**
     * The best sellers among those carts, by revenue then units, at most {@code limit} of them.
     * An empty cart list answers empty rather than scanning the table.
     */
    List<ItemSales> topSellers(Collection<Long> cartIds, int limit);

    /**
     * Active items at or below their reorder point, deepest shortfall first — S2's stock
     * watchlist. Unlike everything else here it is a snapshot of now, not of a window: a
     * watchlist is about what to buy today.
     */
    List<StockWatch> stockWatchlist(int limit);

    /** @param revenue units x the price snapshot each line was sold at, never today's price */
    record ItemSales(long itemId, String name, String category, int units, int revenue) {
    }

    record StockWatch(long itemId, String name, String category, int stock, int reorderAt) {
    }
}
