package dev.gamersden.common.spi;

import java.util.List;
import java.util.Optional;

/**
 * The narrow read {@code session} and {@code billing} need from {@code catalog} — the F&amp;B half
 * of the net-outstanding end guard (ARCHITECTURE.md §5.9: a session ends only when unpaid blocks
 * plus an unsettled cart come to zero) and the F&amp;B lines of {@code GET /sessions/{id}/bill}.
 *
 * <p>Implemented by {@code catalog/domain/CartLookupService}; everything published here is
 * read-only. Both callers read the same cart, so the figure the bill charges for and the figure
 * the end guard refuses on can never disagree.
 */
public interface CartLookup {

    /**
     * The session's cart while it still owes money, and empty once a payment has flipped
     * {@code carts.settled} (or when there is no cart at all).
     */
    Optional<UnsettledCart> unsettledCart(long sessionId);

    /** What that cart owes — 0 when there is nothing unsettled behind the seat. */
    default int unsettledTotal(long sessionId) {
        return unsettledCart(sessionId).map(UnsettledCart::total).orElse(0);
    }

    /** An open cart and its lines, oldest item first. */
    record UnsettledCart(long cartId, List<Line> lines) {

        public int total() {
            return lines.stream().mapToInt(Line::lineTotal).sum();
        }
    }

    /**
     * One line.
     *
     * @param unitPrice the snapshot taken when the line was opened, not the item's current price —
     *                  a mid-sale price edit must never rewrite a bill already on screen
     */
    record Line(long itemId, String name, String category, int qty, int unitPrice) {

        public int lineTotal() {
            return qty * unitPrice;
        }
    }
}
