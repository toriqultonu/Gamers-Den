package dev.gamersden.queue.web;

import dev.gamersden.common.spi.PlayTicketSale;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

/**
 * What {@code POST /play-tickets} returns: {@code {token, printJobId}} plus the sale behind them
 * (api-contract.md, "Play queue").
 *
 * <p>This object is what the idempotency filter stores, so a retry under the same
 * {@code Idempotency-Key} replays it verbatim — the same token, the same transaction and the same
 * print job come back, and no second number comes off the daily counter (invariant §5.2).
 *
 * @param amount what was charged: {@code blocks ×} the console's block rate at the moment of sale
 */
@Schema(name = "PlayTicketSold", description = "A prepaid play-queue token, paid for")
public record PlayTicketSoldView(TokenView token,
                                 long transactionId,
                                 String publicId,
                                 long printJobId,
                                 int amount) {

    public static PlayTicketSoldView of(PlayTicketSale.SoldTicket sold) {
        return new PlayTicketSoldView(TokenView.of(sold.ticket()), sold.transactionId(),
                sold.publicId(), sold.printJobId(), sold.amount());
    }

    /**
     * The token as the POS success state reads it.
     *
     * @param queueEntryId what the Floor seats with; it survives a day rollover, the number does
     *                     not (invariant §5.10)
     * @param tokenNo      printed double-height as {@code TOKEN #NN} on the P6 stub
     */
    @Schema(name = "PlayQueueToken")
    public record TokenView(long queueEntryId,
                            int tokenNo,
                            LocalDate tokenDate,
                            String playerName,
                            String consoleType,
                            int blocks) {

        static TokenView of(dev.gamersden.common.spi.PlayTicketSettlement.IssuedTicket ticket) {
            return new TokenView(ticket.queueEntryId(), ticket.tokenNo(), ticket.tokenDate(),
                    ticket.playerName(), ticket.consoleType(), ticket.blocks());
        }
    }
}
