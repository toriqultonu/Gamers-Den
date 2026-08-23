package dev.gamersden.printing.domain;

import dev.gamersden.common.spi.ExpenseVoucherPrinting;
import dev.gamersden.common.spi.PlayTicketPrinting;
import dev.gamersden.common.spi.SaleReceiptPrinting;
import dev.gamersden.common.spi.ShiftReportPrinting;
import dev.gamersden.printing.repo.PrintJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code printing} package's answer to {@link SaleReceiptPrinting},
 * {@link ShiftReportPrinting}, {@link ExpenseVoucherPrinting} and {@link PlayTicketPrinting} —
 * the only door {@code billing}, {@code shift} and {@code booking} use into {@code print_jobs}
 * (ARCHITECTURE.md §3). The queue worker, the device port and the {@code /print-jobs} endpoints
 * arrive with B18.
 *
 * <p>Two invariants meet in {@link #issueSaleReceipt}:
 *
 * <ul>
 *   <li><strong>§5.3 — the job is part of the money transaction.</strong>
 *       {@link Propagation#MANDATORY} makes that impossible to get wrong: there is no way to queue
 *       a sale ticket except inside the transaction that took the money, so a settle cannot commit
 *       without its receipt and a rolled-back settle cannot leave one behind. A replayed settle
 *       returns the stored response, and with it the same {@code printJobId} — double-print is not
 *       something the worker has to defend against.</li>
 *   <li><strong>§5.5 — the bytes are rendered once.</strong> Rendering happens here, at job
 *       creation, and the result is stored in {@code rendered} / {@code rendered_text}. A retry
 *       re-sends those exact bytes; a reprint is a new job with its own reason band. Nothing
 *       downstream ever recomputes a layout, so the paper, the preview and the audit can never
 *       drift apart.</li>
 * </ul>
 */
@Service
public class PrintJobService implements SaleReceiptPrinting, ShiftReportPrinting,
        ExpenseVoucherPrinting, PlayTicketPrinting {

    private static final Logger log = LoggerFactory.getLogger(PrintJobService.class);

    private final PrintJobRepository jobs;
    private final ReceiptRenderer renderer;

    public PrintJobService(PrintJobRepository jobs, ReceiptRenderer renderer) {
        this.jobs = jobs;
        this.renderer = renderer;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long issueSaleReceipt(SaleReceipt receipt) {
        RenderedDocument rendered = renderer.renderSale(receipt);
        PrintJob job = jobs.save(new PrintJob(PrintJobType.RECEIPT, receipt.transactionId(),
                receipt.deviceId(), receipt.operatorId(), rendered.bytes(), rendered.text()));
        log.info("print job {} queued as {} for transaction {} ({} on {})",
                job.getId(), PrintJobType.RECEIPT, receipt.publicId(), receipt.transactionId(),
                receipt.deviceId());
        return job.getId();
    }

    /**
     * P2 / P3. Same two invariants: the Z is queued inside the transaction that closes the shift,
     * and its bytes are rendered once — a Z is a snapshot of a moment the figures will never be
     * recomputable from, so re-rendering it later could only ever disagree with the paper.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long issueShiftReport(ShiftReport report) {
        PrintJobType type = report.kind() == Kind.Z ? PrintJobType.Z_REPORT : PrintJobType.X_REPORT;
        RenderedDocument rendered = renderer.renderShiftReport(report);
        PrintJob job = jobs.save(new PrintJob(type, report.shiftId(), report.deviceId(),
                report.operatorId(), rendered.bytes(), rendered.text()));
        log.info("print job {} queued as {} for shift {} on {} by staff {}",
                job.getId(), type, report.shiftId(), report.deviceId(), report.operatorId());
        return job.getId();
    }

    /** P4 — queued in the transaction that recorded the expense it is signed against. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long issueExpenseVoucher(ExpenseVoucher voucher) {
        RenderedDocument rendered = renderer.renderExpenseVoucher(voucher);
        PrintJob job = jobs.save(new PrintJob(PrintJobType.EXPENSE_VOUCHER, voucher.expenseId(),
                voucher.deviceId(), voucher.operatorId(), rendered.bytes(), rendered.text()));
        log.info("print job {} queued as {} for expense {} ({} BDT on {})",
                job.getId(), PrintJobType.EXPENSE_VOUCHER, voucher.expenseId(), voucher.amount(),
                voucher.deviceId());
        return job.getId();
    }

    /**
     * P6, standalone — a booking check-in issues a token but takes no money, so there is no sale
     * receipt for the stub to ride on (invariant §5.5). The job still belongs to the transaction
     * that issued the token: {@link Propagation#MANDATORY}, so a check-in that rolls back cannot
     * leave a token printed for a booking that is still waiting.
     *
     * <p>The job references the {@code queue_entries} row rather than the booking or the sale.
     * That is what the Code 128 on the paper encodes, and it is the id that keeps working after a
     * day rollover (invariant §5.10) — so a reprint from the job and a scan from the counter are
     * looking up the same thing.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long issuePlayTicket(PlayTicket ticket) {
        RenderedDocument rendered = renderer.renderPlayTicket(ticket);
        PrintJob job = jobs.save(new PrintJob(PrintJobType.PLAY_TICKET, ticket.queueEntryId(),
                ticket.deviceId(), ticket.operatorId(), rendered.bytes(), rendered.text()));
        log.info("print job {} queued as {} for queue entry {} (TOKEN #{} of {} on {})",
                job.getId(), PrintJobType.PLAY_TICKET, ticket.queueEntryId(), ticket.tokenNo(),
                ticket.tokenDate(), ticket.deviceId());
        return job.getId();
    }
}
