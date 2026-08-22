package dev.gamersden.alert.domain;

import dev.gamersden.alert.repo.AlertRepository;
import dev.gamersden.common.spi.AlertPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code alert} package's answer to {@link AlertPublisher} — the only door another package
 * uses into {@code alerts} (ARCHITECTURE.md §3). The feed endpoints and the SSE {@code alert}
 * event arrive with B19.
 *
 * <p>{@link Propagation#MANDATORY} for the same reason the print job is: an alert is a statement
 * that something happened, so it is written in the transaction that made it happen. A cash
 * discrepancy raised outside the close would outlive a close that rolled back.
 */
@Service
public class AlertService implements AlertPublisher {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final AlertRepository alerts;

    public AlertService(AlertRepository alerts) {
        this.alerts = alerts;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long raise(String type, String title, String body) {
        Alert alert = alerts.save(new Alert(type, title, body));
        log.info("alert {} raised: {} — {}", alert.getId(), type, title);
        return alert.getId();
    }
}
