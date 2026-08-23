package dev.gamersden.printing.domain;

import dev.gamersden.auth.domain.StaffRole;
import dev.gamersden.common.config.VenueTime;
import dev.gamersden.common.error.ConflictException;
import dev.gamersden.common.error.ErrorCode;
import dev.gamersden.common.error.ForbiddenException;
import dev.gamersden.common.error.NotFoundException;
import dev.gamersden.common.security.CurrentStaff;
import dev.gamersden.common.security.StaffPrincipal;
import dev.gamersden.common.spi.ExpenseVoucherPrinting;
import dev.gamersden.common.spi.PlayTicketPrinting;
import dev.gamersden.common.spi.ReceiptCopyPreference;
import dev.gamersden.common.spi.SaleReceiptPrinting;
import dev.gamersden.common.spi.ShiftReportPrinting;
import dev.gamersden.printing.repo.PrintJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * The {@code printing} package's answer to {@link SaleReceiptPrinting},
 * {@link ShiftReportPrinting}, {@link ExpenseVoucherPrinting} and {@link PlayTicketPrinting} —
 * the only door {@code billing}, {@code shift} and {@code booking} use into {@code print_jobs}
 * (ARCHITECTURE.md §3) — and, from B18, what backs the {@code /print-jobs} endpoints.
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
 *       re-sends those exact bytes; a reprint is a new job carrying those same bytes under its own
 *       reason band. Nothing downstream ever recomputes a layout, so the paper, the preview and
 *       the audit can never drift apart.</li>
 * </ul>
 */
@Service
public class PrintJobService implements SaleReceiptPrinting, ShiftReportPrinting,
        ExpenseVoucherPrinting, PlayTicketPrinting {

    private static final Logger log = LoggerFactory.getLogger(PrintJobService.class);

    /** {@code print_jobs.ref_id} is NOT NULL and a test page refers to nothing. */
    private static final long NO_REFERENT = 0L;

    private final PrintJobRepository jobs;
    private final ReceiptRenderer renderer;
    private final ReceiptCopyPreference receiptCopies;
    private final Clock clock;

    public PrintJobService(PrintJobRepository jobs, ReceiptRenderer renderer,
                           ReceiptCopyPreference receiptCopies, Clock clock) {
        this.jobs = jobs;
        this.renderer = renderer;
        this.receiptCopies = receiptCopies;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long issueSaleReceipt(SaleReceipt receipt) {
        RenderedDocument rendered = withCopies(renderer.renderSale(receipt), receipt.deviceId());
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

    // ---- reads ------------------------------------------------------------------------------

    /** {@code GET /print-jobs/{id}} and {@code GET /print-jobs/{id}/render}. */
    @Transactional(readOnly = true)
    public PrintJob require(long id) {
        return jobs.findById(id).orElseThrow(() -> new NotFoundException("Print job", id));
    }

    // ---- operator actions -------------------------------------------------------------------

    /**
     * {@code POST /print-jobs/{id}/retry} — "re-queue FAILED, same bytes" (api-contract.md).
     *
     * <p>Nothing is re-rendered and nothing is copied: the same row goes back to QUEUED and the
     * worker picks it up with the bytes it has had since the money was taken. That is what makes
     * the retry of a mid-print failure reprint the identical ticket rather than a new document
     * that happens to describe the same sale (docs/backend-architecture.md §5).
     *
     * <p>{@code attempts} is deliberately not reset. The column is an audit of how many times this
     * ticket has been pushed at the hardware, and a printer that needed six goes is worth seeing.
     *
     * <p>Only a FAILED job can be retried — 409 on anything else. A QUEUED job is already going to
     * print, and re-queueing a DONE one would be a second copy with no reason recorded, which is
     * exactly what {@link #reprint} exists to make deliberate.
     */
    @Transactional
    public PrintJob retry(long id) {
        PrintJob job = require(id);
        if (job.getStatus() != PrintJobStatus.FAILED) {
            throw new ConflictException(ErrorCode.CONFLICT,
                    "Print job %d is %s — only a FAILED job can be retried".formatted(id, job.getStatus()),
                    Map.of("status", job.getStatus().name()));
        }
        job.requeue();
        log.info("print job {} re-queued after {} attempt(s) (was {})",
                id, job.getAttempts(), job.getError());
        return job;
    }

    /**
     * {@code POST /print-jobs/{id}/reprint} — a new job carrying the original's stored bytes under
     * the reprint band, with its reason recorded and the original linked (api-contract.md;
     * design.md §5 S11 "reprint-mode (reason … required)").
     *
     * <p>Three deliberate choices:
     *
     * <ul>
     *   <li><strong>New job, never a re-run of the old one.</strong> The first ticket is what the
     *       customer was given and what the audit says was printed; it keeps its DONE and its
     *       timestamp. A reprint is a second piece of paper and is recorded as one.</li>
     *   <li><strong>The root's bytes, not the source's.</strong> Reprinting a reprint bands the
     *       original ticket again rather than stacking a band on a band, and links to the same
     *       original — so {@code original_job_id} always points at the paper that was actually
     *       sold, however many times it has been reissued.</li>
     *   <li><strong>Manager+ for someone else's ticket.</strong> The permission matrix
     *       (api-contract.md §1) reads "Void/reprint others' transactions ✓ ✓ ✗": a cashier may
     *       reprint what they printed, and needs a manager for anything else.</li>
     * </ul>
     *
     * <p>The missing-reason case is a 400 before this method is reached — {@code reason} is
     * {@code @NotNull} on the request body, which is the {@code VALIDATION_FAILED} envelope
     * docs/backend-architecture.md §11 asks for ("Reprint … 400 without reason").
     */
    @Transactional
    public PrintJob reprint(long id, ReprintReason reason) {
        PrintJob source = require(id);
        StaffPrincipal staff = CurrentStaff.require();
        if (!source.getOperatorId().equals(staff.id()) && !staff.isAtLeast(StaffRole.MANAGER)) {
            throw new ForbiddenException(
                    "Reprinting another operator's ticket needs a manager (api-contract.md §1)");
        }
        PrintJob root = source.isReprint() && source.getOriginalJobId() != null
                ? require(source.getOriginalJobId())
                : source;
        OffsetDateTime at = VenueTime.now(clock);
        RenderedDocument banded = renderer.withReprintBand(
                new RenderedDocument(root.getRendered(), root.getRenderedText()), reason, at);
        PrintJob copy = new PrintJob(root.getType(), root.getRefId(), root.getDeviceId(),
                staff.id(), banded.bytes(), banded.text());
        copy.markReprintOf(root.getId(), reason);
        PrintJob saved = jobs.save(copy);
        log.info("print job {} queued as a {} reprint of job {} ({} {}) by staff {}",
                saved.getId(), reason, root.getId(), root.getType(), root.getRefId(), staff.id());
        return saved;
    }

    /**
     * The test page {@code POST /printers/{printerId}/test} queues (TASKLIST B18, "test ticket").
     *
     * <p>An ordinary job in every respect — QUEUED, claimed, attempted, DONE or FAILED — because
     * the point of a test ticket is to exercise the path a real one takes, not to bypass it. Its
     * {@code ref_id} is 0: the column is NOT NULL and a test page refers to no artifact.
     */
    @Transactional
    public PrintJob queueTestTicket(String deviceId, PrinterDirectory.Printer printer) {
        StaffPrincipal staff = CurrentStaff.require();
        RenderedDocument rendered = TestTicket.render(printer, deviceId, staff, VenueTime.now(clock));
        PrintJob job = jobs.save(new PrintJob(PrintJobType.TEST, NO_REFERENT, deviceId, staff.id(),
                rendered.bytes(), rendered.text()));
        log.info("print job {} queued as {} for printer {} by staff {}",
                job.getId(), PrintJobType.TEST, printer.id(), staff.id());
        return job;
    }

    /**
     * {@code receipt_copies = 2} → the ticket again after the cut, inside the same job
     * (docs/backend-architecture.md §5).
     *
     * <p>Sale tickets only. A Z report, an expense voucher and a play-ticket stub are one piece of
     * paper by definition — the drawer keeps the Z, the payee signs the voucher, the customer
     * carries the token — and duplicating them would be waste, not a copy.
     */
    private RenderedDocument withCopies(RenderedDocument rendered, String terminal) {
        return receiptCopies.receiptCopies(terminal) > 1
                ? renderer.withReceiptCopy(rendered)
                : rendered;
    }
}
