package dev.gamersden.common.spi;

import java.util.List;
import java.util.Optional;

/**
 * The narrow write the {@code billing} package needs from {@code catalog} — the F&amp;B half of a
 * settle — without reaching for {@code CartRepository} or {@code ItemRepository}
 * (ARCHITECTURE.md §3).
 *
 * <p>This is the only place {@code items.stock} moves for a sale. A cart line <em>reserves</em>
 * stock (B07's {@code OUT_OF_STOCK} guard measures against what other open carts hold); the shelf
 * itself only moves when the money does, and never without a {@code stock_movements} row in the
 * same transaction (§1 money path).
 *
 * <p>Implemented by {@code catalog/domain/CartSettlementService}. Every method joins the caller's
 * transaction — settle is one DB transaction (invariant §5.3).
 */
public interface CartSettlement {

    /** One cart, settled or not — the caller decides whether "already settled" is 404 or 409. */
    Optional<SettleableCart> find(long cartId);

    /** The same, for the cart hanging off a seat. */
    Optional<SettleableCart> findForSession(long sessionId);

    /**
     * Charges the cart: {@code items.stock} down by each line's quantity, one {@code SALE}
     * movement per line referencing {@code txId}, and the cart flipped to settled.
     *
     * <p>Stock is decremented even if that takes a row below zero. The customer already has the
     * drink; refusing here would leave a paid-for bill unrecorded, and the audit trail is what
     * makes the discrepancy visible.
     *
     * @return what the cart came to, so the caller can prove it matches the bill it charged for
     */
    int settle(long cartId, long txId);

    /**
     * The exact inverse, for a void: stock back up, one {@code VOID} movement per line referencing
     * the reversal transaction, and the cart unsettled so its lines are billable again.
     *
     * @return what went back
     */
    int reverse(long cartId, long reversalTxId);

    /**
     * A cart as a settle reads it.
     *
     * @param sessionId the seat it hangs off, or {@code null} for a counter cart
     * @param settled   true once a payment has charged it; its lines are then history
     * @param lines     its lines at their opening price snapshots, oldest item first
     */
    record SettleableCart(long cartId, Long sessionId, boolean settled, List<CartLookup.Line> lines) {

        public int total() {
            return lines.stream().mapToInt(CartLookup.Line::lineTotal).sum();
        }
    }
}
