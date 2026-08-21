package dev.gamersden.member.web;

import dev.gamersden.member.domain.TopupMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /members/{id}/wallet/topup} — {@code {amount, method, paymentRef?}}
 * (api-contract.md, Members). Requires an {@code Idempotency-Key}.
 */
public record TopupRequest(
        @NotNull @Min(1)
        @Schema(description = "Integer BDT added to the wallet")
        Integer amount,
        @NotNull
        @Schema(description = "How the money came in — a wallet cannot fund itself, so no WALLET")
        TopupMethod method,
        @Size(max = 64)
        @Schema(description = "bKash/Nagad TrxID, entered by hand in the MVP")
        String paymentRef) {
}
