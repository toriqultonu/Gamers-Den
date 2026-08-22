package dev.gamersden.billing.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * The body of {@code POST /payments/{id}/void} — {@code {reason}}, and nothing else. The reason is
 * required because it is the only part of a void that cannot be reconstructed later: the money is
 * in the ledgers and the reversal, but why an operator undid a printed sale is only ever recorded
 * here.
 */
@Schema(name = "VoidRequest")
public record VoidRequest(@NotBlank String reason) {
}
