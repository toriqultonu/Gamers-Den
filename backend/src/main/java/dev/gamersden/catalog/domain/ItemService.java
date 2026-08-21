package dev.gamersden.catalog.domain;

import dev.gamersden.catalog.repo.CartLineRepository;
import dev.gamersden.catalog.repo.ItemRepository;
import dev.gamersden.catalog.repo.StockMovementRepository;
import dev.gamersden.common.error.ConflictException;
import dev.gamersden.common.error.ErrorCode;
import dev.gamersden.common.error.NotFoundException;
import dev.gamersden.common.security.CurrentStaff;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The menu (api-contract.md, "Cart &amp; menu"): {@code /items…} CRUD, Manager+ on every write,
 * with one rule that outranks the rest — <strong>stock never moves silently</strong>. Every change
 * to {@code items.stock} writes a {@code stock_movements} row in the same transaction, so the
 * column is only ever a running total of an append-only audit.
 *
 * <p>Two reasons belong to this service: {@code INITIAL} for the opening count a new item is
 * created with, and {@code MANUAL_ADJUST} for a stocktake correction. {@code SALE} and
 * {@code VOID} are written by the settle transaction (B10) — a cart line reserves stock but does
 * not decrement it.
 *
 * <p>Tournament entries and play tickets are deliberately not items: they are first-class payment
 * lines priced from their own tables (V001 comment).
 */
@Service
public class ItemService {

    private static final Logger log = LoggerFactory.getLogger(ItemService.class);

    private final ItemRepository items;
    private final StockMovementRepository movements;
    private final CartLineRepository lines;

    public ItemService(ItemRepository items, StockMovementRepository movements, CartLineRepository lines) {
        this.items = items;
        this.movements = movements;
        this.lines = lines;
    }

    // ---- reads ----------------------------------------------------------------------------

    /**
     * The menu, grouped by category and alphabetical inside it. {@code active} and
     * {@code category} are optional filters; the POS passes {@code active=true}, S10's editor
     * passes nothing and sees the retired rows too.
     */
    @Transactional(readOnly = true)
    public List<ItemStock> menu(Boolean active, ItemCategory category) {
        List<Item> found = select(active, category);
        Map<Long, Integer> held = heldByItem();
        return found.stream()
                .map(item -> ItemStock.of(item, held.getOrDefault(item.getId(), 0)))
                .toList();
    }

    @Transactional(readOnly = true)
    public ItemStock stockOf(Long id) {
        Item item = get(id);
        return ItemStock.of(item, Math.toIntExact(lines.heldOnOpenCarts(item.getId())));
    }

    @Transactional(readOnly = true)
    public Item get(Long id) {
        return items.findById(id).orElseThrow(() -> new NotFoundException("Item", id));
    }

    // ---- writes ---------------------------------------------------------------------------

    /**
     * A new menu row. An opening count is not a bare column write: it lands as an {@code INITIAL}
     * movement so the audit explains where the very first units came from.
     */
    @Transactional
    public Item create(String name, ItemCategory category, int price, int stock, int reorderAt) {
        String trimmed = name.trim();
        if (items.existsByName(trimmed)) {
            throw duplicateName(trimmed);
        }
        Item item = new Item(trimmed, category, price);
        item.setReorderAt(reorderAt);
        Item created = items.save(item);
        if (stock != 0) {
            applyStock(created, stock, StockMovementReason.INITIAL);
        }
        log.info("item {} created as {} {} at {}", created.getId(), category, trimmed, price);
        return created;
    }

    /**
     * Every field optional. {@code stock} is an absolute count, not a delta — staff type what they
     * counted on the shelf — and the difference against the stored value becomes one
     * {@code MANUAL_ADJUST} movement, signed either way. A price edit never rewrites an open
     * cart: lines carry their own {@code unit_price} snapshot.
     */
    @Transactional
    public Item update(Long id, String name, ItemCategory category, Integer price,
                       Integer stock, Integer reorderAt, Boolean active) {
        Item item = get(id);
        if (name != null) {
            String trimmed = name.trim();
            if (!trimmed.equals(item.getName()) && items.existsByName(trimmed)) {
                throw duplicateName(trimmed);
            }
            item.setName(trimmed);
        }
        if (category != null) {
            item.setCategory(category);
        }
        if (price != null) {
            item.setPrice(price);
        }
        if (reorderAt != null) {
            item.setReorderAt(reorderAt);
        }
        if (active != null) {
            item.setActive(active);
        }
        if (stock != null && stock != item.getStock()) {
            applyStock(item, stock - item.getStock(), StockMovementReason.MANUAL_ADJUST);
        }
        log.info("item {} updated", id);
        return item;
    }

    /**
     * Retire an item. The row itself only goes when nothing points at it; once a cart line or a
     * stock movement references it, the history wins and the item is deactivated instead — it
     * leaves the menu either way, and no canonical 409 code exists for "item in use" (§4.4).
     */
    @Transactional
    public void delete(Long id) {
        Item item = get(id);
        if (lines.existsByIdItemId(item.getId()) || movements.existsByItemId(item.getId())) {
            item.setActive(false);
            log.info("item {} deactivated — it has sales or stock history", id);
            return;
        }
        items.delete(item);
        log.info("item {} deleted", id);
    }

    /** One stock change: the column and its audit row, always together. */
    private void applyStock(Item item, int delta, StockMovementReason reason) {
        item.setStock(item.getStock() + delta);
        movements.save(new StockMovement(item.getId(), delta, reason, null,
                CurrentStaff.require().id()));
        log.info("item {} stock {}{} by {} -> {}", item.getId(), delta > 0 ? "+" : "", delta,
                reason, item.getStock());
    }

    private List<Item> select(Boolean active, ItemCategory category) {
        if (active == null) {
            return category == null
                    ? items.findAllByOrderByCategoryAscNameAsc()
                    : items.findByCategoryOrderByNameAsc(category);
        }
        return category == null
                ? items.findByActiveOrderByCategoryAscNameAsc(active)
                : items.findByActiveAndCategoryOrderByNameAsc(active, category);
    }

    private Map<Long, Integer> heldByItem() {
        Map<Long, Integer> held = new HashMap<>();
        lines.heldByItem().forEach(hold -> held.put(hold.itemId(), hold.quantity()));
        return held;
    }

    private static ConflictException duplicateName(String name) {
        return new ConflictException(ErrorCode.DUPLICATE_NAME,
                "Item name \"%s\" is already on the menu".formatted(name), Map.of("name", name));
    }
}
