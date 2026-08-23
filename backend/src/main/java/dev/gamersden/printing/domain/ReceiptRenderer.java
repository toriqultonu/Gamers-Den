package dev.gamersden.printing.domain;

import dev.gamersden.common.spi.ExpenseVoucherPrinting;
import dev.gamersden.common.spi.PlayTicketPrinting;
import dev.gamersden.common.spi.SaleReceiptPrinting;
import dev.gamersden.common.spi.ShiftReportPrinting;

import java.time.OffsetDateTime;

/**
 * Turns an artifact into paper. {@link PrintJobService} renders through this interface and stores
 * whatever comes back, which is what let B17 swap the placeholder for the real ESC/POS templates
 * without moving a caller, a table or a response shape.
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
     * The reprint band (design.md §5 P1) — a reprint is a new job carrying its reason, and the
     * band is what tells a customer holding the paper that this is not the original.
     *
     * <p>It takes the original's stored render rather than the artifact it was made from, because
     * there is nothing left to re-render: invariant §5.5 says the bytes are made once, and a
     * reprint that recomputed a layout could disagree with the paper it claims to replace. So the
     * band is built and the stored bytes are carried through underneath it unchanged.
     *
     * @param original what the first job stored
     * @param reason   why someone is reprinting; B18's endpoint refuses the request without one
     * @param at       when the reprint was asked for, in venue time
     */
    RenderedDocument withReprintBand(RenderedDocument original, ReprintReason reason,
                                     OffsetDateTime at);
}
