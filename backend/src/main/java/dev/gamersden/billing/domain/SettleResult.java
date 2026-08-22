package dev.gamersden.billing.domain;

import java.util.List;

/**
 * What {@code POST /payments} hands back (api-contract.md, "Billing &amp; payments"):
 * {@code {transactionId, printJobId, entryTokens?, queueTokens?}}.
 *
 * <p>Both id fields are the ones a replay has to reproduce exactly — the whole point of invariant
 * §5.3 is that a retried settle returns this same object, so the second tap neither charges again
 * nor prints again.
 *
 * @param entryTokens tournament seed tokens, one per {@code tournamentEntries[]} entry — B12 fills
 *                    this; {@code null} until then, so the field stays off the wire
 * @param queueTokens daily play-queue tokens, one per {@code playTickets[]} ticket — B16 fills this
 */
public record SettleResult(long transactionId,
                           String publicId,
                           long printJobId,
                           List<String> entryTokens,
                           List<String> queueTokens) {

    /** A floor or counter settle: no tournament entries, no play tickets, no tokens to issue. */
    public static SettleResult of(long transactionId, String publicId, long printJobId) {
        return new SettleResult(transactionId, publicId, printJobId, null, null);
    }
}
