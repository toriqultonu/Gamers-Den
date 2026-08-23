package dev.gamersden.printing.domain;

import dev.gamersden.common.spi.ExpenseVoucherPrinting;
import dev.gamersden.common.spi.PlayTicketPrinting;
import dev.gamersden.common.spi.SaleReceiptPrinting;
import dev.gamersden.common.spi.ShiftReportPrinting;

/**
 * Turns an artifact into paper. The seam B17 replaces: {@link PrintJobService} renders through
 * this interface and stores whatever comes back, so swapping the placeholder for the real ESC/POS
 * templates changes no caller, no table and no response shape.
 *
 * <p>Called exactly once per job, inside the money transaction that created it — the bytes are
 * rendered at job creation and never recomputed, which is what makes a retry byte-identical to
 * the paper it is replacing (invariant §5.5).
 */
public interface ReceiptRenderer {

    /** P1 — the sale ticket (design.md §5). */
    RenderedDocument renderSale(SaleReceiptPrinting.SaleReceipt receipt);

    /** P2 / P3 — the Z and X reports; one template, two headings (design.md §5). */
    RenderedDocument renderShiftReport(ShiftReportPrinting.ShiftReport report);

    /** P4 — the petty-cash voucher (design.md §5). */
    RenderedDocument renderExpenseVoucher(ExpenseVoucherPrinting.ExpenseVoucher voucher);

    /**
     * P6 — the play-ticket stub, standalone. A booking check-in takes no money, so its token has
     * no sale receipt to ride on (invariant §5.5); a play ticket sold at the POS renders its P6
     * onto the sale's own job instead (B16).
     */
    RenderedDocument renderPlayTicket(PlayTicketPrinting.PlayTicket ticket);
}
