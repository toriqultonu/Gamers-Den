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

    /**
     * The second copy, after the cut, when {@code terminal_settings.receipt_copies = 2}
     * (docs/backend-architecture.md §5). Composition, not rendering: the sale was laid out once
     * and this puts the same bytes on the paper twice, so the copy the customer signs is
     * character-for-character the copy the drawer keeps.
     *
     * <p>Applied at job creation, not at print time, because {@code rendered} is what a retry
     * re-sends and what S11 previews — a copy that only existed in the worker would make the
     * preview disagree with the paper (invariant §5.5).
     */
    RenderedDocument withReceiptCopy(RenderedDocument original);

    /**
     * The reprint band {@code POST /print-jobs/{id}/reprint} stamps on the new job
     * (api-contract.md, "Print jobs"; design.md §5 "Reprint band").
     *
     * <p>Composition again: the reprint carries the <em>original's stored bytes</em> under a band
     * saying what it is, rather than a fresh render of today's data. A receipt re-rendered a week
     * later against a changed rate card would be a different document with the same number, which
     * is the drift invariant §5.5 exists to prevent.
     */
    RenderedDocument withReprintBand(RenderedDocument original, ReprintReason reason,
                                     java.time.OffsetDateTime at);
}
