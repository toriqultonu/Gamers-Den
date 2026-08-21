package dev.gamersden.session.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * {@code POST /sessions/{id}/blocks} — {@code {delta: +1|-1}} (api-contract.md, Sessions).
 * One block is 30 minutes. Zero is not a request, and the endpoint deliberately takes one block
 * at a time so a retry under the same {@code Idempotency-Key} is unambiguous.
 */
public record BlocksRequest(
        @NotNull
        @Min(-1)
        @Max(1)
        @Schema(description = "+1 buys a 30-minute block at the current rate, -1 returns the "
                + "newest unpaid, unplayed one", allowableValues = {"-1", "1"})
        Integer delta) {
}
