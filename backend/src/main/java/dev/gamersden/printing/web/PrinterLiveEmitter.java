package dev.gamersden.printing.web;

import dev.gamersden.common.events.LiveChange;
import dev.gamersden.common.events.LiveEvent;
import dev.gamersden.common.events.SseHub;
import dev.gamersden.printing.domain.PrinterDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The SSE {@code printer-status} event (ARCHITECTURE.md §4.5) — every attached device with its
 * live status, in the shape {@code GET /printers} answers with.
 *
 * <p>Sent when a job ends and when the venue's default is reassigned, which is exactly when the
 * answer can have changed: the worker has just been at the device, so a job that went FAILED with
 * {@code OUT_OF_PAPER} and one that went DONE are the two ways the counter learns whether it is
 * worth pressing Print. The status in the payload is polled here rather than inferred from the
 * job — the same rule {@code GET /printers} keeps, because a remembered status is worse than none.
 *
 * <p>{@code fallbackExecution} matters here more than anywhere else: the print worker spends most
 * of its life outside a transaction, and a status change announced from there must still be sent
 * rather than silently dropped for want of a commit to hang off.
 */
@Component
public class PrinterLiveEmitter {

    private static final Logger log = LoggerFactory.getLogger(PrinterLiveEmitter.class);

    private final PrinterDirectory printers;
    private final SseHub hub;

    public PrinterLiveEmitter(PrinterDirectory printers, SseHub hub) {
        this.printers = printers;
        this.hub = hub;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPrinterStatusChanged(LiveChange.PrinterStatusChanged changed) {
        if (!hub.hasSubscribers()) {
            return;
        }
        try {
            hub.publish(LiveEvent.PRINTER_STATUS,
                    printers.list().stream().map(PrinterView::of).toList());
        } catch (RuntimeException ex) {
            log.warn("printer-status not sent: {}", ex.toString());
        }
    }
}
