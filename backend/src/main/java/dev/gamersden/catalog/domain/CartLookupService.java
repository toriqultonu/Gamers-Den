package dev.gamersden.catalog.domain;

import dev.gamersden.catalog.repo.CartLineRepository;
import dev.gamersden.catalog.repo.CartRepository;
import dev.gamersden.common.spi.CartLookup;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code catalog} package's answer to {@link CartLookup} — the only door {@code session} uses
 * into {@code carts} / {@code cart_lines}, for the F&amp;B half of the net-outstanding end guard.
 *
 * <p>Cart writes, stock and the {@code OUT_OF_STOCK} guard arrive with B07; this is read-only.
 */
@Service
public class CartLookupService implements CartLookup {

    private final CartRepository carts;
    private final CartLineRepository lines;

    public CartLookupService(CartRepository carts, CartLineRepository lines) {
        this.carts = carts;
        this.lines = lines;
    }

    /** A settled cart owes nothing — the payment that flipped the flag already took its money. */
    @Override
    @Transactional(readOnly = true)
    public int unsettledTotal(long sessionId) {
        return carts.findBySessionId(sessionId)
                .filter(cart -> !cart.isSettled())
                .map(cart -> lines.findByIdCartId(cart.getId()).stream()
                        .mapToInt(line -> line.getQty() * line.getUnitPrice())
                        .sum())
                .orElse(0);
    }
}
