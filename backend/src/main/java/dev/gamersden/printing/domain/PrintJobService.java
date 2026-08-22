package dev.gamersden.printing.domain;

import dev.gamersden.common.spi.SaleReceiptPrinting;
import dev.gamersden.printing.repo.PrintJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code printing} package's answer to {@link SaleReceiptPrinting} — the only door
 * {@code billing} uses into {@code print_jobs} (ARCHITECTURE.md §3). The queue worker, the device
 * port and the {@code /print-jobs} endpoints arrive with B18.
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
public class PrintJobService implements SaleReceiptPrinting {

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
}
