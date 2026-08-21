package dev.gamersden.catalog.web;

import dev.gamersden.catalog.domain.CartType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * {@code POST /carts} — {@code {type: COUNTER}} for a walk-up F&amp;B sale, or
 * {@code {type: SESSION, sessionId}} for the cart that settles with a seat's bill.
 *
 * <p>Sending {@code sessionId} with COUNTER, or omitting it with SESSION, is 400
 * {@code VALIDATION_FAILED} — the two shapes are not interchangeable.
 */
@Schema(name = "CreateCartRequest")
public record CreateCartRequest(
        @NotNull CartType type,
        Long sessionId) {
}
