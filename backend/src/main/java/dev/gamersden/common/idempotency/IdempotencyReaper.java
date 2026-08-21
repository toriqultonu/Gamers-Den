package dev.gamersden.common.idempotency;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Hourly housekeeping for {@code idempotency_keys}. Expiry is already enforced when a key is read,
 * so this only stops the table growing; nothing depends on it having run.
 */
@Component
public class IdempotencyReaper {

    private final IdempotencyStore store;

    public IdempotencyReaper(IdempotencyStore store) {
        this.store = store;
    }

    @Scheduled(initialDelayString = "PT1H", fixedDelayString = "PT1H")
    public void purgeExpiredKeys() {
        store.purgeExpired();
    }
}
