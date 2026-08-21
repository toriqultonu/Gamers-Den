package dev.gamersden.catalog.web;

import dev.gamersden.catalog.domain.CartService;
import dev.gamersden.common.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /carts} — the POS write surface. Every operator rings up sales (api-contract.md §1,
 * permission matrix: "Sessions, POS, payments, prints"); it is the <em>menu</em> that is
 * Manager+, not the till.
 *
 * <p>Neither route is on the idempotency list (§5.2) and neither needs to be: {@code PUT} sets a
 * line rather than incrementing it, so a retry lands on the same quantity. The money write that
 * does need a key is {@code POST /payments} (B10).
 */
@RestController
@RequestMapping("/carts")
@Tag(name = "Carts")
public class CartController {

    private final CartService carts;

    public CartController(CartService carts) {
        this.carts = carts;
    }

    @PostMapping
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "Open a cart",
            description = "201 with a new cart. A session has exactly one cart (carts.session_id "
                    + "is UNIQUE), so asking again for a seat that already has one returns it "
                    + "with 200 instead of failing.")
    public ResponseEntity<CartView> open(@Valid @RequestBody CreateCartRequest request) {
        CartService.Opened opened = carts.open(request.type(), request.sessionId());
        return ResponseEntity.status(opened.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(CartView.of(opened.cart()));
    }

    @PutMapping("/{id}/lines")
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "Set one cart line",
            description = "qty 0 removes the line. A new line snapshots the item's price; "
                    + "changing the quantity keeps that snapshot. 409 OUT_OF_STOCK when the "
                    + "request exceeds the shelf minus what other open carts already hold.")
    public CartView putLine(@PathVariable Long id, @Valid @RequestBody CartLineRequest request) {
        return CartView.of(carts.putLine(id, request.itemId(), request.qty()));
    }
}
