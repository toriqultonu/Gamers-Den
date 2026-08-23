package dev.gamersden.printing.web;

import dev.gamersden.printing.domain.PrinterDirectory;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One row of {@code GET /printers} — "live status: ONLINE, OFFLINE, OUT_OF_PAPER, COVER_OPEN"
 * (api-contract.md, "Print jobs").
 *
 * <p>Live really does mean live: the status was polled from the device while answering this
 * request, not read from a cache. S11 shows it to decide whether pressing Print is worth it, and a
 * remembered ONLINE from before someone opened the lid would be worse than no status at all.
 */
@Schema(name = "Printer", description = "An attached printer and its live status")
public record PrinterView(String id, String name, String status, boolean isDefault) {

    public static PrinterView of(PrinterDirectory.Printer printer) {
        return new PrinterView(printer.id(), printer.name(), printer.status().name(),
                printer.isDefault());
    }
}
