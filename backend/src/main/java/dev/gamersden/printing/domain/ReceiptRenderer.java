package dev.gamersden.printing.domain;

import dev.gamersden.common.spi.SaleReceiptPrinting;

/**
 * Turns a sale into paper. The seam B17 replaces: {@link PrintJobService} renders through this
 * interface and stores whatever comes back, so swapping the placeholder for the real ESC/POS P1
 * template changes no caller, no table and no response shape.
 *
 * <p>Called exactly once per job, inside the money transaction that created it — the bytes are
 * rendered at job creation and never recomputed, which is what makes a retry byte-identical to
 * the paper it is replacing (invariant §5.5).
 */
public interface ReceiptRenderer {

    /** P1 — the sale ticket (design.md §5). */
    RenderedDocument renderSale(SaleReceiptPrinting.SaleReceipt receipt);
}
