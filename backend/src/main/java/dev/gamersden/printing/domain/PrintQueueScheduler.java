package dev.gamersden.printing.domain;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The heartbeat that keeps paper coming out of the printer.
 *
 * <p>A poll rather than an event: a print job is created inside the money transaction (invariant
 * §5.3), so nothing outside that transaction can be told about it until it commits, and a job left
 * QUEUED by a crash or a restart has to be picked up by something that simply looks. One second is
 * well inside the "DONE ≤ 3 s" the cross-cutting matrix expects
 * (docs/backend-architecture.md §11).
 *
 * <p>{@link PrintQueueWorker#dispatch()} returns immediately — the actual printing happens on the
 * per-device threads — so a printer stuck in its retry window never delays the next tick.
 *
 * <p>Switched off under the {@code test} profile, for the same reason the session lock sweeper is:
 * suites drain the queue by hand so their assertions about attempts and status cannot race a
 * background thread. The worker is tested; only the timer is not.
 */
@Component
@ConditionalOnProperty(name = "gamersden.printing.worker-enabled", matchIfMissing = true)
public class PrintQueueScheduler {

    private final PrintQueueWorker worker;

    public PrintQueueScheduler(PrintQueueWorker worker) {
        this.worker = worker;
    }

    @Scheduled(initialDelayString = "${gamersden.printing.poll-interval}",
            fixedDelayString = "${gamersden.printing.poll-interval}")
    public void pollQueue() {
        worker.dispatch();
    }
}
