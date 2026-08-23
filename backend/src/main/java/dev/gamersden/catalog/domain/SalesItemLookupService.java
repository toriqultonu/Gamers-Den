package dev.gamersden.catalog.domain;

import dev.gamersden.catalog.repo.CartLineRepository;
import dev.gamersden.catalog.repo.ItemRepository;
import dev.gamersden.common.spi.SalesItemLookup;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * The {@code catalog} package's answer to {@link SalesItemLookup} — the only door {@code report}
 * uses into {@code cart_lines} and {@code items} (ARCHITECTURE.md §3).
 */
@Service
public class SalesItemLookupService implements SalesItemLookup {

    private final CartLineRepository lines;
    private final ItemRepository items;

    public SalesItemLookupService(CartLineRepository lines, ItemRepository items) {
        this.lines = lines;
        this.items = items;
    }

    /**
     * An empty cart list short-circuits rather than reaching the database: {@code in ()} is not
     * legal SQL, and a window in which nothing was sold has no top sellers by definition.
     */
    @Override
    @Transactional(readOnly = true)
    public List<ItemSales> topSellers(Collection<Long> cartIds, int limit) {
        if (cartIds.isEmpty() || limit <= 0) {
            return List.of();
        }
        return lines.soldOn(cartIds, PageRequest.ofSize(limit)).stream()
                .map(sold -> new ItemSales(sold.itemId(), sold.name(), sold.category().name(),
                        sold.unitCount(), sold.revenueAmount()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockWatch> stockWatchlist(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return items.watchlist(PageRequest.ofSize(limit)).stream()
                .map(item -> new StockWatch(item.getId(), item.getName(),
                        item.getCategory().name(), item.getStock(), item.getReorderAt()))
                .toList();
    }
}
