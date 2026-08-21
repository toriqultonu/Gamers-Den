package dev.gamersden.catalog.domain;

import java.util.List;

/**
 * A cart as the POS panel reads it: the row, its lines already joined to their item names, and
 * the total. The total is summed here and never stored — derived values never are
 * (ARCHITECTURE.md §5.4), and it is the same figure the net-outstanding end guard uses.
 */
public record CartSummary(Cart cart, List<Line> lines) {

    /** COUNTER when no session is behind the cart, SESSION when one is (V001: NULL =&gt; counter). */
    public CartType type() {
        return cart.getSessionId() == null ? CartType.COUNTER : CartType.SESSION;
    }

    public int total() {
        return lines.stream().mapToInt(Line::lineTotal).sum();
    }

    /**
     * One line. {@code unitPrice} is the snapshot taken when the line was opened, not the item's
     * current price — a mid-sale price edit must never rewrite a bill already on screen.
     */
    public record Line(Long itemId, String name, ItemCategory category, int qty, int unitPrice) {

        public int lineTotal() {
            return qty * unitPrice;
        }
    }
}
