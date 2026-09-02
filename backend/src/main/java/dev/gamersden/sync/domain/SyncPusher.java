package dev.gamersden.sync.domain;

import dev.gamersden.common.config.GamersDenProperties;
import dev.gamersden.common.config.VenueTime;
import dev.gamersden.common.config.WebMvcConfig;
import dev.gamersden.common.events.LiveEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.OffsetDateTime;

/**
 * The venue's half of sync: batches unpushed ops to cloud {@code POST /sync/push} every 30 s
 * (docs/backend-architecture.md §9), one way, single writer, no conflicts.
 *
 * <h2>Why nothing is lost when the cloud is down</h2>
 *
 * A row is stamped {@code pushed_at} only after the cloud has answered 2xx. Every other outcome —
 * a refused connection, a timeout, a 500, a 401 — leaves the batch exactly as it was, so the next
 * tick offers it again. The venue keeps trading throughout (§11: "Cloud down a day → venue fully
 * operational; outbox drains on reconnect"), because nothing on the money path waits on this class.
 *
 * <h2>Why a re-push is safe</h2>
 *
 * The one case the stamp cannot cover is a batch the cloud stored and whose response the venue
 * never saw. Those ops are offered again, carrying the {@code opId} minted when they were written,
 * and the receiver skips the ones it already holds — that is what "idempotent by op id" buys.
 *
 * <h2>The bean exists everywhere; only the timer is conditional</h2>
 *
 * Same shape as the print queue: {@link SyncPushScheduler} is switched on by
 * {@code gamersden.sync.push-enabled} (the {@code venue} profile), while the pusher itself is
 * always available so a suite can drive {@link #drain()} by hand instead of racing a background
 * thread.
 */
@Service
public class SyncPusher {

    private static final Logger log = LoggerFactory.getLogger(SyncPusher.class);

    /** The cloud's receiver, spelled once (ARCHITECTURE.md §4.3). */
    public static final String PUSH_PATH = WebMvcConfig.API_BASE_PATH + "/sync/push";

    /** The shared secret's header. The value is {@code SYNC_TOKEN} (ARCHITECTURE.md §6). */
    public static final String TOKEN_HEADER = "X-Sync-Token";

    private final SyncOutboxService outbox;
    private final SyncHealth health;
    private final LiveEvents live;
    private final Clock clock;
    private final RestClient cloud;
    private final String url;
    private final String token;
    private final int batchSize;

    public SyncPusher(SyncOutboxService outbox,
                      SyncHealth health,
                      LiveEvents live,
                      Clock clock,
                      GamersDenProperties properties) {
        this.outbox = outbox;
        this.health = health;
        this.live = live;
        this.clock = clock;
        GamersDenProperties.Sync sync = properties.sync();
        this.url = sync.url() == null ? "" : sync.url().trim();
        this.token = sync.token() == null ? "" : sync.token();
        this.batchSize = sync.batchSize();
        // A short timeout on purpose: this runs on the scheduler, and a cloud that has gone away
        // must cost one tick, not thirty seconds of a held thread.
        SimpleClientHttpRequestFactory transport = new SimpleClientHttpRequestFactory();
        transport.setConnectTimeout(sync.timeout());
        transport.setReadTimeout(sync.timeout());
        this.cloud = RestClient.builder().requestFactory(transport).build();
    }

    /**
     * Pushes batch after batch until the outbox is empty or one fails.
     *
     * @return how many ops the cloud took this time round
     */
    public int drain() {
        if (url.isEmpty()) {
            // Nothing to push to. Not a failure — an unconfigured mirror is not an offline one,
            // and the chip must not read OFFLINE on a venue that was never given a cloud.
            return 0;
        }
        int pushed = 0;
        while (true) {
            SyncOutboxService.Batch batch = outbox.pending(batchSize);
            if (batch.isEmpty()) {
                health.succeeded(VenueTime.now(clock));
                break;
            }
            if (!send(batch)) {
                break;
            }
            pushed += batch.size();
            if (batch.size() < batchSize) {
                break;
            }
        }
        live.syncStatusChanged();
        return pushed;
    }

    /** @return true when the cloud took this batch and the rows have been stamped */
    private boolean send(SyncOutboxService.Batch batch) {
        OffsetDateTime at = VenueTime.now(clock);
        try {
            cloud.post()
                    .uri(url + PUSH_PATH)
                    .header(TOKEN_HEADER, token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new SyncPushRequest(batch.ops()))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException unreachable) {
            // Every failure is the same failure here: the batch stays unpushed and is offered
            // again next tick. Logged at WARN once per attempt, not per op — a day offline should
            // not fill the log with the same line 2,880 times over.
            health.failed(at, unreachable.getMessage());
            log.warn("sync push of {} op(s) failed: {}", batch.size(), unreachable.toString());
            return false;
        }
        outbox.markPushed(batch.ids());
        health.succeeded(at);
        log.info("sync pushed {} op(s) to {}", batch.size(), url);
        return true;
    }
}
