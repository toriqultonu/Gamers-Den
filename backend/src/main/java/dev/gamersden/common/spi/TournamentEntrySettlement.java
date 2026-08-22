package dev.gamersden.common.spi;

import java.util.List;

/**
 * The narrow write the {@code billing} package needs from {@code tournament} — the
 * {@code tournamentEntries[]} half of {@code POST /payments} (api-contract.md, "Billing &amp;
 * payments"; docs/tournaments.md §5) — without reaching for the tournament repositories
 * (ARCHITECTURE.md §3).
 *
 * <p>Two calls rather than one, because a settle has to know what the entries cost <em>before</em>
 * it can write the transaction they will hang off, and {@code tournament_entries.tx_id} is
 * {@code NOT NULL}:
 *
 * <ol>
 *   <li>{@link #quote} takes the tournament row lock, refuses a full or closed event, and hands
 *       back the fee and the seed each entry will get.</li>
 *   <li>{@link #register} inserts the rows once the transaction id exists.</li>
 * </ol>
 *
 * <p>The lock taken in step 1 is held to commit, so nothing can fill the last slot between the two
 * — 409 {@code TOURNAMENT_FULL} is decided against the count as it is at the moment of the write,
 * and a settle that is refused leaves no entry, no money and no paper behind (invariant §5.3).
 * Both methods are {@link org.springframework.transaction.annotation.Propagation#MANDATORY} on the
 * implementation for the same reason: a registration outside the money transaction would be a
 * player nobody charged.
 */
public interface TournamentEntrySettlement {

    /**
     * Prices and seeds the entries about to be sold, refusing anything that cannot be registered.
     *
     * @param memberName the name of the member on the bill, used when no {@code playerName} was
     *                   typed (docs/tournaments.md §5); {@code null} on a walk-in sale
     * @throws dev.gamersden.common.error.ConflictException 409 {@code TOURNAMENT_NOT_OPEN} when
     *                                                      the event is not selling, 409
     *                                                      {@code TOURNAMENT_FULL} past the cap
     */
    List<QuotedEntry> quote(List<EntrySale> sales, String memberName);

    /**
     * Writes the quoted entries against the transaction that paid for them, in sale order.
     *
     * @param memberId the member the sale was attached to, or {@code null} for a walk-in ticket
     */
    List<RegisteredEntry> register(long txId, Long memberId, List<QuotedEntry> quotes);

    /** One {@code tournamentEntries[]} element, exactly as the contract spells it. */
    record EntrySale(long tournamentId, String playerName) {
    }

    /**
     * What one entry will cost and which seed it will take.
     *
     * @param seed the per-tournament sale order, printed as {@code TOKEN #NN} — never the daily
     *             queue counter (invariant §5.10)
     */
    record QuotedEntry(long tournamentId,
                       String tournamentName,
                       String playerName,
                       int fee,
                       int seed) {
    }

    /** A written entry. {@code qrToken} is the opaque payload of the P5 QR (§7). */
    record RegisteredEntry(long entryId,
                           long tournamentId,
                           String tournamentName,
                           String playerName,
                           int seed,
                           String qrToken) {
    }
}
