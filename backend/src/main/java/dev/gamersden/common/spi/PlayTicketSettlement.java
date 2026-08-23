package dev.gamersden.common.spi;

import java.time.LocalDate;
import java.util.List;

/**
 * The narrow write the {@code billing} package needs from {@code queue} — the
 * {@code playTickets[]} half of {@code POST /payments} (api-contract.md, "Billing &amp; payments";
 * docs/bookings.md §3) — without reaching for {@code queue_entries} or {@code token_seq}
 * (ARCHITECTURE.md §3).
 *
 * <p>The exact shape {@link TournamentEntrySettlement} uses, and for the same reason: a settle has
 * to know what the tickets cost <em>before</em> it can write the transaction they hang off, and
 * {@code queue_entries.tx_id} is {@code NOT NULL}.
 *
 * <ol>
 *   <li>{@link #quote} prices each ticket off the console's rate card at the moment of sale and
 *       refuses an unknown console type, before anything is written.</li>
 *   <li>{@link #register} allocates the daily tokens and writes the queue entries once the
 *       transaction id exists.</li>
 * </ol>
 *
 * <p>Nothing here asks whether a console is free. That is the whole point of a play ticket: it is
 * sellable while every console is busy (docs/bookings.md §3), which is why the queue exists at
 * all.
 *
 * <p>Both methods are {@link org.springframework.transaction.annotation.Propagation#MANDATORY} on
 * the implementation — a token issued outside the money transaction would be prepaid time nobody
 * charged for, and a rolled-back settle must not burn a number off the daily counter
 * (invariants §5.3, §5.10).
 */
public interface PlayTicketSettlement {

    /**
     * Prices the tickets about to be sold.
     *
     * @param memberName the name of the member on the bill, used when no {@code playerName} was
     *                   typed; {@code null} on a walk-up sale
     * @throws dev.gamersden.common.error.ValidationFailedException 400 on an unknown console type
     *                                                             or a non-positive block count
     */
    List<QuotedTicket> quote(List<TicketSale> sales, String memberName);

    /** Issues one daily token per quoted ticket, against the transaction that paid for them. */
    List<IssuedTicket> register(long txId, List<QuotedTicket> quotes);

    /**
     * Kills the tokens a voided sale paid for: every entry of {@code txId} still WAITING becomes
     * REFUNDED, in the caller's transaction.
     *
     * <p>A void hands the money back (invariant §5.7), so the prepaid time it bought has to stop
     * being seatable in the same breath — otherwise the reversal is a free hour. Entries already
     * SEATED are left alone: that time has been played, and the session it is on is the void's
     * problem, not the queue's.
     *
     * @return how many tokens were revoked
     */
    int revoke(long txId);

    /** One {@code playTickets[]} element, exactly as the contract spells it. */
    record TicketSale(String consoleType, int blocks, String playerName) {
    }

    /**
     * What one ticket costs.
     *
     * @param blockPrice the rate-card snapshot the prepaid {@code session_blocks} will be born
     *                   carrying when the token is finally seated (invariant §5.9)
     */
    record QuotedTicket(String consoleType, String playerName, int blocks, int blockPrice) {

        /** {@code blocks ×} the block rate at the moment of sale. */
        public int amount() {
            return blocks * blockPrice;
        }
    }

    /**
     * A written token.
     *
     * @param queueEntryId the row id — the Code 128 payload on P6, and the key that keeps the
     *                     token working after a day rollover (invariant §5.10)
     * @param tokenNo      the daily sequence, printed double-height as {@code TOKEN #NN}
     */
    record IssuedTicket(long queueEntryId,
                        int tokenNo,
                        LocalDate tokenDate,
                        String playerName,
                        String consoleType,
                        int blocks,
                        int amount) {
    }
}
