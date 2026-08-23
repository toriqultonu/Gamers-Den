package dev.gamersden.printing.domain;

/**
 * How wide the paper is, in the only two units a template cares about: characters of Font A, and
 * printer dots for a barcode module.
 *
 * <p>design.md §5 assumes 80 mm at 203 dpi and calls 58 mm "a config switch" — it is one of the
 * open flags (ARCHITECTURE.md §8), because nobody has confirmed the printer model yet. Both
 * widths render the same templates; only the character grid and the Code 128 module width move.
 *
 * <p>The module width has to move. design.md asks for a module of at least 0.33 mm, which at
 * 203 dpi (1 dot = 0.125 mm) is 3 dots — and 3 dots is what 80 mm paper gets. On 58 mm the print
 * area is 384 dots, and a Code 128 of a dozen characters needs roughly 11 modules per character
 * plus quiet zones: at 3 dots that overruns the paper and the scanner gets a clipped symbol,
 * which is worse than a narrow one. So 58 mm drops to 2 dots (0.25 mm) and is flagged here rather
 * than silently printing off the edge.
 */
public enum PaperWidth {

    /** 80 mm / 203 dpi, 576 dots — the documented default. */
    MM_80(48, 3),

    /** 58 mm / 203 dpi, 384 dots — the config switch, narrower barcode modules included. */
    MM_58(32, 2);

    private final int columns;
    private final int barcodeModuleDots;

    PaperWidth(int columns, int barcodeModuleDots) {
        this.columns = columns;
        this.barcodeModuleDots = barcodeModuleDots;
    }

    /** Characters per line at Font A — the width every template lays out to. */
    public int columns() {
        return columns;
    }

    /** {@code GS w} module width for Code 128 (design.md §5 "Barcode / QR"). */
    public int barcodeModuleDots() {
        return barcodeModuleDots;
    }

    /**
     * The takings matrix on P2/P3 is a six-column grid that only fits on 80 mm paper; the narrow
     * roll stacks each method's categories under it instead.
     */
    public boolean fitsTakingsGrid() {
        return columns >= MM_80.columns;
    }
}
