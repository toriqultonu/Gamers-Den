package dev.gamersden.catalog.domain;

import dev.gamersden.catalog.repo.CartLineRepository;
import dev.gamersden.catalog.repo.CartRepository;
import dev.gamersden.catalog.repo.ItemRepository;
import dev.gamersden.common.spi.CartLookup;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code catalog} package's answer to {@link CartLookup} — the only door {@code session} and
 * {@code billing} use into {@code carts} / {@code cart_lines}, for the F&amp;B half of the
 * net-outstanding end guard and the F&amp;B lines of the bill.
 *
 * <p>Cart writes, stock and the {@code OUT_OF_STOCK} guard live in {@link CartService} and
 * {@link ItemService}; this door stays read-only.
 */
@Service
public class CartLookupService implements CartLookup {

    private final CartRepository carts;
    private final CartLineRepository lines;
    private final ItemRepository items;

    public CartLookupService(CartRepository carts, CartLineRepository lines, ItemRepository items) {
        this.carts = carts;
        this.lines = lines;
        this.items = items;
    }

    /** A settled cart owes nothing — the payment that flipped the flag already took its money. */
    @Override
    @Transactional(readOnly = true)
    public Optional<UnsettledCart> unsettledCart(long sessionId) {
        return carts.findBySessionId(sessionId)
                .filter(cart -> !cart.isSettled())
                .map(cart -> new UnsettledCart(cart.getId(), describe(cart.getId())));
    }

    /** One extra query for the item names, whatever the line count — never one per line. */
    private List<Line> describe(Long cartId) {
        List<CartLine> found = lines.findByIdCartIdOrderByIdItemIdAsc(cartId);
        if (found.isEmpty()) {
            return List.of();
        }
        Map<Long, Item> byId = new HashMap<>();
        items.findAllById(found.stream().map(CartLine::getItemId).toList())
                .forEach(item -> byId.put(item.getId(), item));
        return found.stream()
                .map(line -> {
                    Item item = byId.get(line.getItemId());
                    return new Line(line.getItemId(), item.getName(), item.getCategory().name(),
                            line.getQty(), line.getUnitPrice());
                })
                .toList();
    }
}
