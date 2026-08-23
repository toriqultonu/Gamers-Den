package dev.gamersden.printing.domain;

/**
 * What a template produces: the byte stream that goes to the printer and the character-grid text
 * that S11 previews. They are rendered together, once, and stored together on the job — the
 * preview matches the paper because it was made from the same pass (invariant §5.5,
 * docs/backend-architecture.md §4).
 *
 * <p>How wide the grid is belongs to {@link PaperWidth}, not here: 48 columns at 80 mm, 32 at the
 * 58 mm switch, and a template lays out to whichever it was given.
 */
public record RenderedDocument(byte[] bytes, String text) {
}
