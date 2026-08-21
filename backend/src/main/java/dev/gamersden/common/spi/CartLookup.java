package dev.gamersden.common.spi;

/**
 * The narrow read the {@code session} package needs from {@code catalog} — the F&amp;B half of the
 * net-outstanding end guard (ARCHITECTURE.md §5.9: a session ends only when unpaid blocks plus an
 * unsettled cart come to zero).
 *
 * <p>Implemented by {@code catalog/domain/CartLookupService}. Carts, lines and stock land in B07;
 * everything published here is read-only.
 */
public interface CartLookup {

    /**
     * What the session's cart still owes: the sum of its lines while the cart is unsettled, and 0
     * once a payment has flipped {@code carts.settled} (or when there is no cart at all).
     */
    int unsettledTotal(long sessionId);
}
