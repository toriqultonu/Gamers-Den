package dev.gamersden.tournament.web;

import dev.gamersden.common.spi.TournamentEntrySale;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What {@code POST /tournaments/{id}/entries} returns: the sale, its receipt job, and the ticket.
 *
 * <p>This object is what the idempotency filter stores, so a retry under the same
 * {@code Idempotency-Key} replays it verbatim — the same transaction, the same print job and the
 * same {@code qrToken} come back, and the player is registered exactly once (invariant §5.2).
 *
 * @param qrToken the QR payload on the printed stub, returned here because the terminal that sold
 *                the ticket is the one that has to hand it over
 */
@Schema(name = "EntrySold", description = "A tournament entry sold at the counter")
public record EntrySoldView(long transactionId,
                            String publicId,
                            long printJobId,
                            long entryId,
                            int seed,
                            String qrToken) {

    public static EntrySoldView of(TournamentEntrySale.Sold sold) {
        return new EntrySoldView(sold.transactionId(), sold.publicId(), sold.printJobId(),
                sold.entryId(), sold.seed(), sold.qrToken());
    }
}
