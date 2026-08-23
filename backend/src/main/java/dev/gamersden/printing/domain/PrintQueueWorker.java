package dev.gamersden.printing.domain;

import dev.gamersden.common.config.GamersDenProperties;
import dev.gamersden.printing.repo.PrintJobRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The print queue (docs/backend-architecture.md §5). One ticket at a time per device, claimed with
 * {@code FOR UPDATE SKIP LOCKED}, {@code QUEUED → PRINTING → DONE | FAILED}, three transport
 * attempts two seconds apart, and a specific failure plus an alert when it gives up.
 *
 * <h2>Why one thread per device</h2>
 *
 * §5 asks for a single-threaded worker per device so tickets never interleave. Each device gets
 * its own single-thread executor, so a printer that is out of paper spends its four seconds of
 * retries without holding up a second counter's queue. Within a device the work is strictly
 * serial; {@link PrinterPort#write} is atomic per ticket as well, so even two devices resolving to
 * the same physical printer cannot shuffle two receipts together.
 *
 * <h2>What "device" means here</h2>
 *
 * {@code print_jobs.device_id} is the terminal that created the job — that is what {@code billing},
 * {@code shift}, {@code booking} and {@code queue} pass, and partitioning on it is what keeps two
 * counters' tickets apart. Which physical printer that resolves to is {@link PrinterDirectory}'s
 * question, not this one's.
 *
 * <h2>Attempts</h2>
 *
 * Three attempts belong to the <em>claim</em>, not to the job's lifetime: a staff retry from S11
 * buys three more. {@code print_jobs.attempts} accumulates across all of them, because it is an
 * audit of how much this ticket cost the counter, not a countdown.
 *
 * <h2>Failure is never the request's problem</h2>
 *
 * By the time a job reaches this class the money is committed (invariant §5.3). A printer that is
 * out of paper therefore fails a <em>job</em>, never a settle: the transaction stands, the ticket
 * goes FAILED with its cause, an alert is raised, and the operator reprints the stored bytes from
 * S11 once the paper is in.
 */
@Service
public class PrintQueueWorker {

    private static final Logger log = LoggerFactory.getLogger(PrintQueueWorker.class);

    private final PrintJobRepository jobs;
    private final PrintQueueStore store;
    private final PrinterDirectory printers;
    private final int maxAttempts;
    private final Duration backoff;

    private final Map<String, ExecutorService> deviceWorkers = new ConcurrentHashMap<>();

    public PrintQueueWorker(PrintJobRepository jobs, PrintQueueStore store,
                            PrinterDirectory printers, GamersDenProperties properties) {
        this.jobs = jobs;
        this.store = store;
        this.printers = printers;
        this.maxAttempts = properties.printing().maxAttempts();
        this.backoff = properties.printing().retryBackoff();
    }

    // ---- dispatch ---------------------------------------------------------------------------

    /**
     * Hands every device with waiting paper to its own worker thread and returns without waiting.
     * The scheduled trigger calls this; it must not block the scheduler on a dead printer.
     *
     * <p>Re-dispatching a device that is already draining is harmless — the executor is
     * single-threaded, so the second task finds an empty queue when the first one is done.
     */
    public void dispatch() {
        for (String deviceId : jobs.deviceIdsWithStatus(PrintJobStatus.QUEUED)) {
            workerFor(deviceId).execute(() -> {
                try {
                    drain(deviceId);
                } catch (RuntimeException e) {
                    log.error("print worker for device {} aborted", deviceId, e);
                }
            });
        }
    }

    /**
     * Prints everything queued for one device, on the calling thread, and answers how many tickets
     * it got through. This is the whole worker — the executors above only decide which thread runs
     * it — so a test drives the real path by calling it directly, the way the session lock sweeper
     * is driven.
     */
    public int drain(String deviceId) {
        int handled = 0;
        while (true) {
            Optional<Claim> claim = store.claimNext(deviceId);
            if (claim.isEmpty()) {
                return handled;
            }
            print(claim.get());
            handled++;
        }
    }

    /** Every device, on the calling thread — the suite-level "drain the queue". */
    public int drainAll() {
        int handled = 0;
        for (String deviceId : jobs.deviceIdsWithStatus(PrintJobStatus.QUEUED)) {
            handled += drain(deviceId);
        }
        return handled;
    }

    // ---- one ticket -------------------------------------------------------------------------

    /**
     * The transport loop. Status is polled before every attempt — DLE EOT is the only way to tell
     * "out of paper" from "unplugged" (docs/backend-architecture.md §5), and a printer that was
     * fine a second ago may not be now.
     */
    private void print(Claim claim) {
        Optional<PrinterPort> port = printers.findPortFor(claim.deviceId());
        if (port.isEmpty()) {
            // Nothing to print on at all — the same outcome as an unplugged printer, and the same
            // alert. One attempt, because there is nothing to attempt again.
            store.fail(claim, claim.attemptsBefore() + 1, PrintFailure.OFFLINE);
            return;
        }
        PrintFailure failure = PrintFailure.TRANSPORT;
        int attempts = claim.attemptsBefore();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            attempts++;
            try {
                PrinterStatus status = port.get().status();
                if (status != PrinterStatus.ONLINE) {
                    failure = status.asFailure();
                    log.warn("print job {} attempt {}/{}: printer {} is {}",
                            claim.jobId(), attempt, maxAttempts, port.get().id(), status);
                } else {
                    port.get().write(claim.bytes());
                    store.complete(claim, attempts);
                    return;
                }
            } catch (PrinterTransportException e) {
                failure = e.failure();
                log.warn("print job {} attempt {}/{} failed: {}",
                        claim.jobId(), attempt, maxAttempts, e.getMessage());
            } catch (RuntimeException e) {
                failure = PrintFailure.TRANSPORT;
                log.warn("print job {} attempt {}/{} failed", claim.jobId(), attempt, maxAttempts, e);
            }
            if (attempt < maxAttempts) {
                pause();
            }
        }
        store.fail(claim, attempts, failure);
    }

    /** The 2 s the venue waits between attempts; milliseconds under the {@code test} profile. */
    private void pause() {
        try {
            Thread.sleep(backoff.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- lifecycle --------------------------------------------------------------------------

    private ExecutorService workerFor(String deviceId) {
        return deviceWorkers.computeIfAbsent(deviceId, id -> Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "print-" + id);
            thread.setDaemon(true);
            return thread;
        }));
    }

    @PreDestroy
    public void shutdown() {
        List<ExecutorService> workers = List.copyOf(deviceWorkers.values());
        deviceWorkers.clear();
        workers.forEach(ExecutorService::shutdown);
        for (ExecutorService worker : workers) {
            try {
                // Long enough for a ticket in flight to finish, short enough not to hang shutdown
                // behind a printer that has stopped answering.
                worker.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * A claimed ticket, carried out of its transaction so the print can happen outside one.
     *
     * @param attemptsBefore what {@code print_jobs.attempts} already stood at — a staff retry adds
     *                       to the count rather than restarting it
     */
    public record Claim(long jobId, String deviceId, int attemptsBefore, byte[] bytes,
                        PrintJobType type, long refId) {
    }
}
