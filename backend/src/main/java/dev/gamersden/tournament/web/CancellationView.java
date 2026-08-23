package dev.gamersden.tournament.web;

import dev.gamersden.tournament.domain.TournamentService;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * What {@code POST /tournaments/{id}/cancel} returns: the event as it now stands, and the money
 * that went back out with it.
 *
 * @param entriesRefunded every entry that still owed money
 * @param refunds         one negative transaction per originating sale — a free event has none
 */
@Schema(name = "TournamentCancellation")
public record CancellationView(TournamentView tournament,
                               int entriesRefunded,
                               List<RefundView> refunds) {

    public static CancellationView of(TournamentService.Cancellation cancellation, int entries) {
        return new CancellationView(
                // A called-off event never had a champion, so there is no name to carry.
                TournamentView.of(cancellation.tournament(), entries, null),
                cancellation.entriesRefunded(),
                cancellation.refunds().stream()
                        .map(refund -> new RefundView(refund.transactionId(), refund.publicId(),
                                refund.amount()))
                        .toList());
    }

    /** @param amount negative, as every refund transaction is (invariant §5.7) */
    @Schema(name = "TournamentRefund")
    public record RefundView(long transactionId, String publicId, int amount) {
    }
}
