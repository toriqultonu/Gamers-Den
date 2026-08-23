package dev.gamersden.queue.domain;

import dev.gamersden.common.spi.QueuePreSoldLookup;
import dev.gamersden.queue.repo.QueueEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code queue} package's answer to {@link QueuePreSoldLookup} — the only door {@code report}
 * uses into {@code queue_entries} (ARCHITECTURE.md §3).
 *
 * <p>Tokens are counted whatever day they were issued on. A WAITING token stays valid across the
 * midnight rollover (invariant §5.10), and the money behind it is owed play until somebody sits
 * down or it is refunded.
 */
@Service
public class QueuePreSoldLookupService implements QueuePreSoldLookup {

    private final QueueEntryRepository entries;

    public QueuePreSoldLookupService(QueueEntryRepository entries) {
        this.entries = entries;
    }

    @Override
    @Transactional(readOnly = true)
    public PreSoldTokens waitingPlayTickets() {
        QueueEntryRepository.PreSoldRow row = entries.waitingPlayTickets();
        return new PreSoldTokens(row.getTokens(), row.getAmount());
    }
}
