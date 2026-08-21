package dev.gamersden.catalog.web;

import dev.gamersden.catalog.domain.ItemCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * {@code PATCH /items/{id}} — Manager+. Every field optional; omitted fields are left alone.
 *
 * <p>{@code stock} is the absolute count staff read off the shelf, not a delta. The difference
 * against the stored value becomes one signed {@code MANUAL_ADJUST} row in
 * {@code stock_movements}, in the same transaction as the column write.
 */
@Schema(name = "UpdateItemRequest")
public record UpdateItemRequest(
        @Size(min = 1, max = 60) String name,
        ItemCategory category,
        @PositiveOrZero Integer price,
        @PositiveOrZero Integer stock,
        @PositiveOrZero Integer reorderAt,
        Boolean active) {
}
