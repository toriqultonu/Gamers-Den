package dev.gamersden.tournament.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * {@code POST /tournaments/{id}/entries} — the counter route: take the fee, register the player,
 * print the stub. Any role sells (docs/tournaments.md §1).
 *
 * <p>No {@code redeemPoints} and no member: a ticket bought at the counter has no bill to attach
 * loyalty to, exactly as an F&amp;B counter sale has none. A member buying an entry does it from
 * their seat's bill, through {@code POST /payments}, where their points and wallet are in play.
 */
@Schema(name = "SellEntryRequest", description = "Sell one tournament entry at the counter")
public record SellEntryRequest(@Size(max = 80) String playerName,
                               @NotEmpty @Valid List<SplitRequest> splits) {

    /** One tender row; the same shape as {@code POST /payments} splits. */
    @Schema(name = "EntrySplit")
    public record SplitRequest(@NotNull String method, int amount, String paymentRef) {
    }
}
