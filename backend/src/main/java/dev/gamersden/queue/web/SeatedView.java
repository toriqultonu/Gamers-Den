package dev.gamersden.queue.web;

import dev.gamersden.queue.domain.PlayQueueService;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What {@code POST /play-queue/{id}/seat} returns: the token, now SEATED, and the session it was
 * seated onto (api-contract.md, "Play queue").
 *
 * <p>The session summary is deliberately small — the Floor re-reads {@code GET /sessions/{id}} for
 * the live countdown, and everything here is derived, never stored (invariant §5.4). What it is
 * for is the one thing the operator needs to see immediately: the prepaid blocks landed already
 * paid, so {@code netOutstanding} is zero and the seat can be started, played and ended without a
 * second payment (invariant §5.9).
 */
@Schema(name = "QueueEntrySeated", description = "A token seated on a console")
public record SeatedView(QueueEntryView entry, SeatedSessionView session) {

    public static SeatedView of(PlayQueueService.Seated seated) {
        return new SeatedView(QueueEntryView.of(seated.entry()),
                new SeatedSessionView(seated.session().sessionId(), seated.session().stationId(),
                        seated.stationName(), seated.session().state(), seated.session().blocks(),
                        seated.session().paidBlocks(), seated.session().remainingSeconds(),
                        seated.session().netOutstanding()));
    }

    /**
     * @param paidBlocks     the prepaid blocks, born carrying the sale's {@code paid_tx_id}
     * @param netOutstanding zero on a fresh seat from a token — nothing more is owed until extra
     *                       time is bought
     */
    @Schema(name = "SeatedSession")
    public record SeatedSessionView(long id,
                                    long stationId,
                                    String stationName,
                                    String state,
                                    int blocks,
                                    int paidBlocks,
                                    long remainingSeconds,
                                    int netOutstanding) {
    }
}
