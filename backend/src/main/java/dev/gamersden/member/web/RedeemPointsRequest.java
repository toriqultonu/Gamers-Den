package dev.gamersden.member.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * {@code POST /members/{id}/wallet/redeem-points} — 1 point = ৳1 into the wallet
 * (api-contract.md, Members). Requires an {@code Idempotency-Key}. More points than the member
 * holds is 409 {@code INSUFFICIENT_POINTS}.
 */
public record RedeemPointsRequest(
        @NotNull @Min(1)
        @Schema(description = "Points to convert; the wallet gains the same number of BDT")
        Integer points) {
}
