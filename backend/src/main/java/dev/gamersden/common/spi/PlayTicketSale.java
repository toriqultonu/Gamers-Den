package dev.gamersden.common.spi;

/**
 * The narrow write the {@code queue} package needs from {@code billing} — {@code POST
 * /play-tickets}, the standalone alias for selling one walk-up ticket (api-contract.md, "Play
 * queue").
 *
 * <p>The mirror of {@link PlayTicketSettlement}, and the same shape {@code tournament} uses for
 * {@code POST /tournaments/{id}/entries}: the alias is not a second money path, it is a second
 * door into the one settle. {@code billing} still writes the transaction, its tender and the print
 * job in one transaction, and {@code queue} still writes its entry inside it through
 * {@link PlayTicketSettlement} (invariant §5.3).
 *
 * <p>Unlike {@link BookingSale}, the order carries no amount: a play ticket has no package fee and
 * no snapshot to agree with, so the settle prices it off the rate card and tenders exactly what it
 * priced. There is nothing for two figures to disagree about.
 */
public interface PlayTicketSale {

    /** Takes the money, issues the token and queues its P6, in one transaction. */
    SoldTicket sell(TicketOrder order);

    /**
     * @param method     the payment method as a string, so {@code common} stays free of the
     *                   {@code billing} enum; an unknown one is 400
     * @param paymentRef the bKash/Nagad TrxID; required on those methods, ignored elsewhere
     */
    record TicketOrder(String consoleType,
                       int blocks,
                       String playerName,
                       String method,
                       String paymentRef) {
    }

    /** @param printJobId the one job carrying the P1 receipt and this ticket's P6 stub */
    record SoldTicket(long transactionId,
                      String publicId,
                      long printJobId,
                      PlayTicketSettlement.IssuedTicket ticket,
                      int amount) {
    }
}
