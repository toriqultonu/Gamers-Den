package dev.gamersden.billing.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * What {@code POST /payments} hands back (api-contract.md, "Billing &amp; payments"):
 * {@code {transactionId, printJobId, entryTokens?, queueTokens?}}.
 *
 * <p>Both id fields are the ones a replay has to reproduce exactly — the whole point of invariant
 * §5.3 is that a retried settle returns this same object, so the second tap neither charges again
 * nor prints again.
 *
 * @param entryTokens tournament seed tokens, one per {@code tournamentEntries[]} entry — the
 *                    opaque QR payload printed on each P5 stub; {@code null} when there are none,
 *                    so the field stays off the wire
 * @param queueTokens daily play-queue tokens, one per {@code playTickets[]} ticket
 */
public record SettleResult(long transactionId,
                           String publicId,
                           long printJobId,
                           List<String> entryTokens,
                           List<QueueToken> queueTokens) {

    /** A floor or counter settle: no tournament entries, no play tickets, no tokens to issue. */
    public static SettleResult of(long transactionId, String publicId, long printJobId) {
        return new SettleResult(transactionId, publicId, printJobId, null, null);
    }

    /**
     * One issued play-queue token.
     *
     * <p>An object rather than the bare number the paper carries: after a day rollover the number
     * alone is ambiguous, so {@code queueEntryId} is what the Floor seats with, {@code tokenNo} is
     * what is printed, and {@code tokenDate} says which day's counter it came off (invariant
     * §5.10).
     */
    public record QueueToken(long queueEntryId, int tokenNo, LocalDate tokenDate) {
    }
}
