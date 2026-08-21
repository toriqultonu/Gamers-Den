package dev.gamersden.catalog.domain;

import dev.gamersden.catalog.repo.CartLineRepository;
import dev.gamersden.catalog.repo.CartRepository;
import dev.gamersden.catalog.repo.ItemRepository;
import dev.gamersden.common.error.ConflictException;
import dev.gamersden.common.error.ErrorCode;
import dev.gamersden.common.error.NotFoundException;
import dev.gamersden.common.error.ValidationFailedException;
import dev.gamersden.common.spi.SessionLookup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Carts and their lines (api-contract.md, "Cart &amp; menu"): {@code POST /carts} opens one,
 * {@code PUT /carts/{id}/lines} upserts a line and {@code qty: 0} removes it. Every operator may
 * ring up a sale — this is the POS half of the permission matrix, not the menu half.
 *
 * <p>Three rules:
 *
 * <ol>
 *   <li><strong>Unit price is a snapshot.</strong> A line records the item's price the moment it
 *       is opened and keeps it while the qty moves, so a {@code PATCH /items} mid-sale moves the
 *       next line and never one already on the bill.</li>
 *   <li><strong>Stock is reserved, not decremented.</strong> The column only moves at settle
 *       (§1 money path), so {@code OUT_OF_STOCK} measures the request against {@code stock} minus
 *       everything held on other open carts — otherwise the last unit could sit on two carts and
 *       both would settle.</li>
 *   <li><strong>A settled cart is closed.</strong> Its transaction snapshot is immutable (§5.4);
 *       new lines would silently change a bill that has already been paid and printed.</li>
 * </ol>
 *
 * <p>The session behind a cart is read through {@link SessionLookup}, never
 * {@code SessionRepository} (§3: no cross-package repository access).
 */
@Service
public class CartService {

    private static final Logger log = LoggerFactory.getLogger(CartService.class);

    private final CartRepository carts;
    private final CartLineRepository lines;
    private final ItemRepository items;
    private final SessionLookup sessions;

    public CartService(CartRepository carts, CartLineRepository lines, ItemRepository items,
                       SessionLookup sessions) {
        this.carts = carts;
        this.lines = lines;
        this.items = items;
        this.sessions = sessions;
    }

    // ---- POST /carts ----------------------------------------------------------------------

    /**
     * Opens a counter cart, or hands back the seat's cart. {@code carts.session_id} is UNIQUE
     * (V001), so a session has one cart for its whole life: asking twice returns the same cart
     * rather than failing, which is what makes the POS panel safe to re-open.
     *
     * @return the cart, and whether this call is the one that created it
     */
    @Transactional
    public Opened open(CartType type, Long sessionId) {
        if (type == CartType.COUNTER) {
            if (sessionId != null) {
                throw ValidationFailedException.onField("sessionId",
                        "A COUNTER cart has no session — omit sessionId or send type SESSION");
            }
            Cart created = carts.save(new Cart(null));
            log.info("counter cart {} opened", created.getId());
            return new Opened(describe(created), true);
        }
        if (sessionId == null) {
            throw ValidationFailedException.onField("sessionId",
                    "A SESSION cart needs the sessionId it belongs to");
        }
        sessions.liveSession(sessionId).orElseThrow(() -> new NotFoundException(
                "No live session %d to hang a cart on".formatted(sessionId)));
        Optional<Cart> existing = carts.findBySessionId(sessionId);
        if (existing.isPresent()) {
            return new Opened(describe(existing.get()), false);
        }
        Cart created = carts.save(new Cart(sessionId));
        log.info("cart {} opened on session {}", created.getId(), sessionId);
        return new Opened(describe(created), true);
    }

    // ---- PUT /carts/{id}/lines ------------------------------------------------------------

    /**
     * Upsert one line. {@code qty: 0} removes it — and removing a line that is not there is a
     * no-op, so the button is safe to double-tap. 409 {@code OUT_OF_STOCK} when the requested
     * quantity is more than the shelf can still cover.
     */
    @Transactional
    public CartSummary putLine(Long cartId, Long itemId, int qty) {
        Cart cart = get(cartId);
        if (cart.isSettled()) {
            throw new ConflictException(ErrorCode.CONFLICT,
                    "Cart %d is settled — open a new one for another sale".formatted(cartId),
                    Map.of("cartId", cartId));
        }
        Item item = items.findById(itemId).orElseThrow(() -> new NotFoundException("Item", itemId));
        CartLineId id = new CartLineId(cart.getId(), item.getId());

        if (qty == 0) {
            lines.findById(id).ifPresent(line -> {
                lines.delete(line);
                log.info("cart {} line for item {} removed", cartId, itemId);
            });
            return describe(cart);
        }

        if (!item.isActive()) {
            throw ValidationFailedException.onField("itemId",
                    "\"%s\" is not on the menu".formatted(item.getName()));
        }
        requireStock(cart, item, qty);

        CartLine line = lines.findById(id).orElse(null);
        if (line == null) {
            lines.save(new CartLine(cart.getId(), item.getId(), qty, item.getPrice()));
            log.info("cart {} line for item {} opened at {} x{}", cartId, itemId, item.getPrice(), qty);
        } else {
            line.setQty(qty);
            log.info("cart {} line for item {} set to x{}", cartId, itemId, qty);
        }
        return describe(cart);
    }

    /**
     * The shelf, minus what other open carts already hold. Reads {@code items.stock} — the column
     * only moves at settle, so a cart that has not been paid for still has to fit inside it.
     */
    private void requireStock(Cart cart, Item item, int qty) {
        int held = Math.toIntExact(lines.heldElsewhere(item.getId(), cart.getId()));
        int available = Math.max(item.getStock() - held, 0);
        if (qty > available) {
            throw new ConflictException(ErrorCode.OUT_OF_STOCK,
                    "Only %d of \"%s\" left".formatted(available, item.getName()),
                    Map.of("itemId", item.getId(), "requested", qty, "available", available));
        }
    }

    private Cart get(Long cartId) {
        return carts.findById(cartId).orElseThrow(() -> new NotFoundException("Cart", cartId));
    }

    /** One extra query for the item names, whatever the line count — never one per line. */
    private CartSummary describe(Cart cart) {
        List<CartLine> found = lines.findByIdCartIdOrderByIdItemIdAsc(cart.getId());
        if (found.isEmpty()) {
            return new CartSummary(cart, List.of());
        }
        Map<Long, Item> byId = new HashMap<>();
        items.findAllById(found.stream().map(CartLine::getItemId).toList())
                .forEach(item -> byId.put(item.getId(), item));
        return new CartSummary(cart, found.stream()
                .map(line -> {
                    Item item = byId.get(line.getItemId());
                    return new CartSummary.Line(line.getItemId(), item.getName(), item.getCategory(),
                            line.getQty(), line.getUnitPrice());
                })
                .toList());
    }

    /** {@code POST /carts} answers 201 when it opened the cart and 200 when it reused a seat's. */
    public record Opened(CartSummary cart, boolean created) {
    }
}
