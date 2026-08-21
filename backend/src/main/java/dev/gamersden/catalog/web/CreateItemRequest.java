package dev.gamersden.catalog.web;

import dev.gamersden.catalog.domain.ItemCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /items} — Manager+. 409 {@code DUPLICATE_NAME} when the name is taken.
 *
 * <p>An opening {@code stock} is audited as an {@code INITIAL} stock movement, not written
 * straight onto the column.
 */
@Schema(name = "CreateItemRequest")
public record CreateItemRequest(
        @NotBlank @Size(max = 60) String name,
        @NotNull ItemCategory category,
        @NotNull @PositiveOrZero Integer price,
        @PositiveOrZero Integer stock,
        @PositiveOrZero Integer reorderAt) {

    public int stockOrZero() {
        return stock == null ? 0 : stock;
    }

    public int reorderAtOrZero() {
        return reorderAt == null ? 0 : reorderAt;
    }
}
