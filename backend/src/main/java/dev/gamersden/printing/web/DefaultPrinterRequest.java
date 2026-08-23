package dev.gamersden.printing.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * {@code PUT /printers/default} — which attached printer the venue prints on
 * (api-contract.md, "Print jobs").
 *
 * <p>The id is one from {@code GET /printers}; anything else is a 404. Choosing a printer that is
 * not on the bus would silently send every subsequent ticket to whichever device happened to be
 * enumerated first, which is the one failure mode this endpoint exists to prevent.
 */
@Schema(name = "DefaultPrinterRequest", description = "Which attached printer to print on")
public record DefaultPrinterRequest(@NotBlank String printerId) {
}
