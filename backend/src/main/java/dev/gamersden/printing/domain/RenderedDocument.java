package dev.gamersden.printing.domain;

import java.nio.charset.StandardCharsets;

/**
 * What a template produces: the byte stream that goes to the printer and the 48-column text that
 * S11 previews. They are rendered together, once, and stored together on the job — the preview
 * matches the paper exactly because it was made from the same pass (invariant §5.5,
 * docs/backend-architecture.md §4).
 */
public record RenderedDocument(byte[] bytes, String text) {

    /** Paper width in characters at 80 mm / Font A — the width every template lays out to. */
    public static final int COLUMNS = 48;

    /**
     * A document whose bytes are just its text. Correct only for the placeholder renderer — B17's
     * ESC/POS templates carry control sequences the preview does not.
     */
    public static RenderedDocument plainText(String text) {
        return new RenderedDocument(text.getBytes(StandardCharsets.UTF_8), text);
    }
}
