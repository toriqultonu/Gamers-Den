package dev.gamersden.common.spi;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;

/**
 * The narrow read the {@code booking} package needs from {@code queue} — the token number to put
 * on a checked-in booking's row ("Token #NN · waiting — seat from Floor", docs/bookings.md §2) —
 * without reaching for {@code queue_entries} (ARCHITECTURE.md §3).
 *
 * <p>Deliberately separate from {@link QueueTokenIssuing}: that door writes, inside a money
 * transaction; this one only reads, for a list. Implemented by
 * {@code queue/domain/QueueTokenService}.
 */
public interface QueueTokenLookup {

    /** The tokens behind those {@code queue_entries} ids, keyed by id; unknown ids are absent. */
    Map<Long, Token> tokensOf(Collection<Long> queueEntryIds);

    /** @param tokenNo the daily sequence printed as {@code TOKEN #NN} */
    record Token(long queueEntryId, int tokenNo, LocalDate tokenDate, String status) {
    }
}
