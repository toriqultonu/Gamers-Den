package dev.gamersden.printing.web;

import dev.gamersden.printing.domain.ReprintReason;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * {@code POST /print-jobs/{id}/reprint} — {@code {reason}} (api-contract.md, "Print jobs").
 *
 * <p>{@code @NotNull} is the requirement, not a nicety: design.md §5 marks the reason
 * <em>required</em> in S11's reprint mode and docs/backend-architecture.md §11 puts "400 without
 * reason" in the cross-cutting matrix. A second copy of a receipt with no recorded reason is a
 * second copy nobody can account for, which is precisely what a dispute needs to be able to
 * account for.
 *
 * <p>Typed as the enum, so an unknown reason is a 400 from the same envelope rather than a free
 * text field that quietly accepts anything.
 */
@Schema(name = "ReprintRequest", description = "Why this ticket is being printed again")
public record ReprintRequest(@NotNull ReprintReason reason) {
}
