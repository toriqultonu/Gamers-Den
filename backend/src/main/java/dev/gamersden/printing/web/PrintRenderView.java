package dev.gamersden.printing.web;

import dev.gamersden.printing.domain.PrintJob;
import dev.gamersden.printing.domain.RenderedDocument;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * {@code GET /print-jobs/{id}/render} — "stored 48-col text for S11" (api-contract.md).
 *
 * <p>Stored, never recomputed (design.md §5 S11: "Shows the stored render (never recomputed)").
 * The text came off the same pass that produced the bytes on the paper, so the preview is the
 * paper — which is the whole reason invariant §5.5 renders once and keeps both.
 *
 * @param columns the paper width the text is laid out to, so the client sizes its character grid
 *                from the server rather than assuming 80 mm
 * @param bytes   how long the ESC/POS stream is; S11 shows nothing of it, but a support call about
 *                a ticket that will not print starts with "is there anything in it at all"
 */
@Schema(name = "PrintRender", description = "The stored character-grid render of a print job")
public record PrintRenderView(long id, String type, int columns, String text, int bytes) {

    public static PrintRenderView of(PrintJob job) {
        return new PrintRenderView(job.getId(), job.getType().name(), RenderedDocument.COLUMNS,
                job.getRenderedText(), job.getRendered().length);
    }
}
