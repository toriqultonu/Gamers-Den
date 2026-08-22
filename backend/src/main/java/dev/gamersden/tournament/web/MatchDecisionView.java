package dev.gamersden.tournament.web;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * What {@code POST /tournaments/{id}/matches/{mid}/winner} returns: the whole event as it now
 * stands, plus what the operator is about to do next.
 *
 * <p>It carries the {@link TournamentDetailView} fields flattened rather than nested, so one
 * response re-renders the bracket columns, the player list and the card without a second read —
 * a result moves a player up the tree, and the tree is what the screen is.
 *
 * @param nextMatchId        the match the winner advanced into; null when this was the final
 * @param suggestedStationId the console that match would land on if it were started now (§4);
 *                           null when nothing is free, which is information, not an error
 * @param champion           true when the final has just been decided — the event is DONE and
 *                           every console it held is back on the floor
 */
@Schema(name = "MatchDecision")
public record MatchDecisionView(TournamentView tournament,
                                List<TournamentEntryView> entries,
                                List<Long> stationIds,
                                List<TournamentMatchView> bracket,
                                Long nextMatchId,
                                Long suggestedStationId,
                                boolean champion) {

    public static MatchDecisionView of(TournamentDetailView detail, Long nextMatchId,
                                       Long suggestedStationId, boolean champion) {
        return new MatchDecisionView(detail.tournament(), detail.entries(), detail.stationIds(),
                detail.bracket(), nextMatchId, suggestedStationId, champion);
    }
}
