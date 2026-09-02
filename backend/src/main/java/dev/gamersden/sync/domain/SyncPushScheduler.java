package dev.gamersden.sync.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * The 30 s heartbeat of docs/backend-architecture.md §9.
 *
 * <p>A poll rather than an event, for the same reason the print queue is one: the op is written
 * inside the money transaction, so nothing outside it can be told until it commits, and ops left
 * behind by a crash, a restart or a day of cloud downtime have to be picked up by something that
 * simply looks.
 *
 * <p>On under the {@code venue} profile alone ({@code gamersden.sync.push-enabled}). The cloud
 * receives and does not push; dev and test drive {@link SyncPusher#drain()} by hand so their
 * assertions cannot race a timer.
 */
@Component
@ConditionalOnProperty(name = "gamersden.sync.push-enabled", havingValue = "true")
public class SyncPushScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncPushScheduler.class);

    private final SyncPusher pusher;

    public SyncPushScheduler(SyncPusher pusher) {
        this.pusher = pusher;
    }

    @Scheduled(initialDelayString = "${gamersden.sync.push-interval-seconds}",
            fixedDelayString = "${gamersden.sync.push-interval-seconds}",
            timeUnit = TimeUnit.SECONDS)
    public void push() {
        int pushed = pusher.drain();
        if (pushed > 0) {
            log.debug("sync tick pushed {} op(s)", pushed);
        }
    }
}
