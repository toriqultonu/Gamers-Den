package dev.gamersden.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** {@code cart_lines} — {@code unitPrice} is a snapshot, so price edits never rewrite a bill. */
@Entity
@Table(name = "cart_lines")
public class CartLine {

    @EmbeddedId
    private CartLineId id;

    @Column(nullable = false)
    private int qty;

    @Column(name = "unit_price", nullable = false)
    private int unitPrice;

    protected CartLine() {
    }

    public CartLine(Long cartId, Long itemId, int qty, int unitPrice) {
        this.id = new CartLineId(cartId, itemId);
        this.qty = qty;
        this.unitPrice = unitPrice;
    }

    public CartLineId getId() {
        return id;
    }

    public Long getCartId() {
        return id.getCartId();
    }

    public Long getItemId() {
        return id.getItemId();
    }

    public int getQty() {
        return qty;
    }

    public void setQty(int qty) {
        this.qty = qty;
    }

    public int getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(int unitPrice) {
        this.unitPrice = unitPrice;
    }
}
