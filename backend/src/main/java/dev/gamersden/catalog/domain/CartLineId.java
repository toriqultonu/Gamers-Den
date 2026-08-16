package dev.gamersden.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/** Composite key of {@code cart_lines}: one line per item per cart. */
@Embeddable
public class CartLineId implements Serializable {

    @Column(name = "cart_id", nullable = false)
    private Long cartId;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    protected CartLineId() {
    }

    public CartLineId(Long cartId, Long itemId) {
        this.cartId = cartId;
        this.itemId = itemId;
    }

    public Long getCartId() {
        return cartId;
    }

    public Long getItemId() {
        return itemId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof CartLineId that
                && Objects.equals(cartId, that.cartId)
                && Objects.equals(itemId, that.itemId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(cartId, itemId);
    }
}
