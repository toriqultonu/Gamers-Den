package dev.gamersden.printing.domain;

import dev.gamersden.common.config.VenueTime;
import dev.gamersden.common.spi.AlertPublisher;
import dev.gamersden.printing.repo.PrintJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;

/**
 * The three short transactions the print queue is made of — claim, complete, fail — kept in their
 * own bean on purpose.
 *
 * <p>{@link PrintQueueWorker} spends most of its time <em>outside</em> a transaction, blocked on
 * hardware, and calls in here at each state change. If these methods lived on the worker itself
 * they would be self-invocations and Spring's proxy would never see them: the {@code @Transactional}
 * annotations would silently do nothing and every state write would run on its own auto-commit —
 * which is precisely the bug that would let a claim and its {@code FOR UPDATE SKIP LOCKED} lock
 * come apart. Two beans, so the boundary is real.
 */
@Service
public class PrintQueueStore {

    private static final Logger log = LoggerFactory.getLogger(PrintQueueStore.class);

    /** {@code alerts.type} for a ticket that never made it onto paper. */
    public static final String PRINTER_FAILED = "PRINTER_FAILED";

    private final PrintJobRepository jobs;
    private final AlertPublisher alerts;
    private final Clock clock;

    public PrintQueueStore(PrintJobRepository jobs, AlertPublisher alerts, Clock clock) {
        this.jobs = jobs;
        this.alerts = alerts;
        this.clock = clock;
    }

    /**
     * Takes the next ticket for one device and flips it {@code QUEUED → PRINTING}, in one short
     * transaction (docs/backend-architecture.md §5).
     *
     * <p>The row is claimed with {@code FOR UPDATE SKIP LOCKED}, so a second worker walks past it
     * rather than queueing behind it, and the flip commits before any bytes move — a crash
     * mid-print therefore leaves the job visibly stuck in PRINTING instead of being silently
     * reprinted by the next poll.
     *
     * <p>The bytes leave with the claim rather than being re-read afterwards: they were written
     * once at job creation and cannot have changed (invariant §5.5), and the print happens outside
     * any transaction.
     */
    @Transactional
    public Optional<PrintQueueWorker.Claim> claimNext(String deviceId) {
        return jobs.claimNextFor(deviceId).map(job -> {
            job.markPrinting();
            return new PrintQueueWorker.Claim(job.getId(), deviceId, job.getAttempts(),
                    job.getRendered(), job.getType(), job.getRefId());
        });
    }

    @Transactional
    public void complete(PrintQueueWorker.Claim claim, int attempts) {
        PrintJob job = jobs.findById(claim.jobId()).orElseThrow();
        job.markDone(attempts, VenueTime.now(clock));
        log.info("print job {} printed on {} after {} attempt(s) ({} {})",
                claim.jobId(), claim.deviceId(), attempts, claim.type(), claim.refId());
    }

    /**
     * FAILED with the specific cause, plus the alert docs/backend-architecture.md §5 asks for —
     * in one transaction, because an alert about a failure that rolled back would be an alert
     * about nothing ({@link AlertPublisher} is {@code MANDATORY} for exactly that reason).
     *
     * <p>The job is not re-queued. The automatic attempts are spent; what happens next is a human
     * decision at S11 — load paper and retry the identical bytes, or reprint with a reason.
     */
    @Transactional
    public void fail(PrintQueueWorker.Claim claim, int attempts, PrintFailure failure) {
        PrintJob job = jobs.findById(claim.jobId()).orElseThrow();
        job.markFailed(attempts, failure, VenueTime.now(clock));
        alerts.raise(PRINTER_FAILED,
                "Print failed — " + failure.describe(),
                ("%s for #%d gave up after %d attempt(s) on %s. Fix the printer and retry from the "
                        + "print preview; the ticket prints exactly as it was rendered.")
                        .formatted(claim.type(), claim.refId(), attempts, claim.deviceId()));
        log.warn("print job {} FAILED ({}) after {} attempt(s) on {}",
                claim.jobId(), failure, attempts, claim.deviceId());
    }
}
