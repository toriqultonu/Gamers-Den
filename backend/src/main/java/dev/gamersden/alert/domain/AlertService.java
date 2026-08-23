package dev.gamersden.alert.domain;

import dev.gamersden.alert.repo.AlertRepository;
import dev.gamersden.common.error.NotFoundException;
import dev.gamersden.common.events.LiveEvents;
import dev.gamersden.common.spi.AlertPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The {@code alert} package's answer to {@link AlertPublisher} — the only door another package
 * uses into {@code alerts} (ARCHITECTURE.md §3) — plus the feed behind {@code GET /alerts}.
 *
 * <p>{@link Propagation#MANDATORY} on {@link #raise} for the same reason the print job is: an
 * alert is a statement that something happened, so it is written in the transaction that made it
 * happen. A cash discrepancy raised outside the close would outlive a close that rolled back.
 *
 * <p>The SSE {@code alert} event follows from the same call, but only once that transaction
 * commits — {@link LiveEvents} publishes the fact and {@code AlertLiveEmitter} sends it after
 * commit (ARCHITECTURE.md §4.5).
 */
@Service
public class AlertService implements AlertPublisher {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final AlertRepository alerts;
    private final LiveEvents live;

    public AlertService(AlertRepository alerts, LiveEvents live) {
        this.alerts = alerts;
        this.live = live;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long raise(String type, String title, String body) {
        Alert alert = alerts.save(new Alert(type, title, body));
        log.info("alert {} raised: {} — {}", alert.getId(), type, title);
        live.alertRaised(alert.getId());
        return alert.getId();
    }

    // ---- the feed ------------------------------------------------------------------------------

    /** {@code GET /alerts} — newest first; {@code unread=true} is what the bell badge counts. */
    @Transactional(readOnly = true)
    public List<Alert> feed(boolean unreadOnly) {
        return unreadOnly ? alerts.findByReadFalseOrderByIdDesc() : alerts.findByOrderByIdDesc();
    }

    @Transactional(readOnly = true)
    public Alert get(long id) {
        return alerts.findById(id).orElseThrow(() -> new NotFoundException("Alert", id));
    }

    @Transactional(readOnly = true)
    public long unreadCount() {
        return alerts.countByReadFalse();
    }

    /**
     * Marks one row read. Deliberately not idempotency-guarded and deliberately not a 409 when it
     * is already read: "I have seen this" is not money, and pressing it twice means the same thing
     * as pressing it once.
     */
    @Transactional
    public Alert markRead(long id) {
        Alert alert = alerts.findById(id).orElseThrow(() -> new NotFoundException("Alert", id));
        alert.setRead(true);
        return alert;
    }

    /** The rail's "mark all read". */
    @Transactional
    public int markAllRead() {
        int cleared = alerts.markAllRead();
        log.info("{} alert(s) marked read", cleared);
        return cleared;
    }
}
