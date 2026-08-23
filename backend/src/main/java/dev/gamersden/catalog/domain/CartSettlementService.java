package dev.gamersden.catalog.domain;

import dev.gamersden.catalog.repo.CartLineRepository;
import dev.gamersden.catalog.repo.CartRepository;
import dev.gamersden.catalog.repo.ItemRepository;
import dev.gamersden.catalog.repo.StockMovementRepository;
import dev.gamersden.common.error.NotFoundException;
import dev.gamersden.common.security.CurrentStaff;
import dev.gamersden.common.spi.AlertPublisher;
import dev.gamersden.common.spi.CartLookup;
import dev.gamersden.common.spi.CartSettlement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code catalog} package's answer to {@link CartSettlement} — the only door {@code billing}
 * uses to charge a cart and to hand it back (ARCHITECTURE.md §3).
 *
 * <p>This is where the shelf finally moves. A cart line reserves stock and nothing more
 * ({@link CartService}); {@code items.stock} changes here, at settle, and never without a
 * {@code stock_movements} row carrying the transaction that caused it — the same rule
 * {@link ItemService} keeps for stocktakes, with {@code SALE} and {@code VOID} as the reasons and
 * a {@code ref_tx_id} that reconciles.
 *
 * <p>{@link Propagation#MANDATORY} on both writes: they are halves of a payment or a void
 * (invariant §5.3). Stock that moved while the money rolled back is shrinkage with no receipt to
 * explain it.
 *
 * <p>It is also where the low-stock alert of the operator feed is raised (B19), because this is
 * the only place a sale moves the shelf. It fires on the <em>crossing</em> — the sale that takes
 * an item from above its reorder point to at or below it — so the feed carries one card per item
 * running down rather than one per bottle sold afterwards.
 */
@Service
public class CartSettlementService implements CartSettlement {

    private static final Logger log = LoggerFactory.getLogger(CartSettlementService.class);

    private final CartRepository carts;
    private final CartLineRepository lines;
    private final ItemRepository items;
    private final StockMovementRepository movements;
    private final AlertPublisher alerts;

    public CartSettlementService(CartRepository carts,
                                 CartLineRepository lines,
                                 ItemRepository items,
                                 StockMovementRepository movements,
                                 AlertPublisher alerts) {
        this.carts = carts;
        this.lines = lines;
        this.items = items;
        this.movements = movements;
        this.alerts = alerts;
    }

    // ---- reads ----------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Optional<SettleableCart> find(long cartId) {
        return carts.findById(cartId).map(this::describe);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SettleableCart> findForSession(long sessionId) {
        return carts.findBySessionId(sessionId).map(this::describe);
    }

    // ---- writes ---------------------------------------------------------------------------

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int settle(long cartId, long txId) {
        return move(cartId, txId, StockMovementReason.SALE, true);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int reverse(long cartId, long reversalTxId) {
        return move(cartId, reversalTxId, StockMovementReason.VOID, false);
    }

    /**
     * One direction of the same movement. {@code charging} decides the sign of the stock delta and
     * which way {@code settled} flips; everything else — one audit row per line, referencing the
     * transaction that caused it — is identical, which is what makes a void the exact inverse of
     * the sale it undoes.
     *
     * <p>Stock is never refused here. The customer is holding the drink and the money is being
     * taken in the same breath, so the honest record of an over-sold shelf is a negative count and
     * a movement that explains it, not a 409 that loses the sale.
     */
    private int move(long cartId, long txId, StockMovementReason reason, boolean charging) {
        Cart cart = carts.findById(cartId).orElseThrow(() -> new NotFoundException("Cart", cartId));
        long staffId = CurrentStaff.require().id();
        List<CartLine> found = lines.findByIdCartIdOrderByIdItemIdAsc(cartId);
        int total = 0;
        for (CartLine line : found) {
            Item item = items.findById(line.getItemId())
                    .orElseThrow(() -> new NotFoundException("Item", line.getItemId()));
            int delta = charging ? -line.getQty() : line.getQty();
            int before = item.getStock();
            item.setStock(before + delta);
            movements.save(new StockMovement(item.getId(), delta, reason, txId, staffId));
            raiseLowStockIfCrossed(item, before);
            if (item.getStock() < 0) {
                log.warn("item {} \"{}\" went to {} on {} by transaction {} — the shelf is over-sold; "
                        + "correct it with a stocktake", item.getId(), item.getName(),
                        item.getStock(), reason, txId);
            }
            total += line.getQty() * line.getUnitPrice();
        }
        cart.setSettled(charging);
        log.info("cart {} {} for {} BDT by transaction {} ({} lines)",
                cartId, charging ? "settled" : "released", total, txId, found.size());
        return total;
    }

    /**
     * The stock watchlist's alert, raised the once. An item at or below {@code reorder_at} before
     * this sale was already on the feed; only the sale that pushes it over the line writes a card,
     * so a busy evening does not bury the discrepancy and printer alerts under a run on Coke.
     *
     * <p>Inside the settle transaction, like every alert: an alert about a sale that rolled back
     * would be an alert about stock that never moved.
     */
    private void raiseLowStockIfCrossed(Item item, int before) {
        if (before <= item.getReorderAt() || item.getStock() > item.getReorderAt()) {
            return;
        }
        alerts.raise(AlertPublisher.LOW_STOCK,
                "%s is down to %d".formatted(item.getName(), item.getStock()),
                "%s crossed its reorder point of %d — %d left on the shelf."
                        .formatted(item.getName(), item.getReorderAt(), item.getStock()));
    }

    /** One extra query for the item names, whatever the line count — never one per line. */
    private SettleableCart describe(Cart cart) {
        List<CartLine> found = lines.findByIdCartIdOrderByIdItemIdAsc(cart.getId());
        if (found.isEmpty()) {
            return new SettleableCart(cart.getId(), cart.getSessionId(), cart.isSettled(), List.of());
        }
        Map<Long, Item> byId = new HashMap<>();
        items.findAllById(found.stream().map(CartLine::getItemId).toList())
                .forEach(item -> byId.put(item.getId(), item));
        return new SettleableCart(cart.getId(), cart.getSessionId(), cart.isSettled(), found.stream()
                .map(line -> {
                    Item item = byId.get(line.getItemId());
                    return new CartLookup.Line(line.getItemId(), item.getName(),
                            item.getCategory().name(), line.getQty(), line.getUnitPrice());
                })
                .toList());
    }
}
