package dev.gamersden.tournament.web;

import dev.gamersden.tournament.domain.MatchExecutionService;
import dev.gamersden.tournament.domain.Tournament;
import dev.gamersden.tournament.domain.TournamentEntry;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * {@code GET /tournaments/{id}} — the card, who is in it, which consoles it holds, and the bracket.
 *
 * <p>{@code bracket} is empty until the event is drawn: the pre-bracket screen is the registered
 * player list with its slots-left note, and the bracket columns replace it the moment the draw
 * lands (design.md S12). Nothing else in the payload changes at that point, so the FE reads one
 * shape either way.
 *
 * <p>Every match in it carries its own {@code remainingSeconds}, so the bracket columns, the "Now
 * on" tiles and the match board all tick from this one read (docs/tournaments.md §4). The finance
 * numbers deliberately are <em>not</em> here — they are Manager+ and live behind their own
 * endpoint (§6).
 */
@Schema(name = "TournamentDetail")
public record TournamentDetailView(TournamentView tournament,
                                   List<TournamentEntryView> entries,
                                   @Schema(description = "Consoles blocked for this event")
                                   List<Long> stationIds,
                                   @Schema(description = "First round first; empty before the draw")
                                   List<TournamentMatchView> bracket) {

    public static TournamentDetailView of(Tournament tournament, List<TournamentEntry> entries,
                                          List<Long> stationIds,
                                          List<MatchExecutionService.LiveMatchView> bracket) {
        return new TournamentDetailView(TournamentView.of(tournament, entries),
                entries.stream().map(TournamentEntryView::of).toList(), stationIds,
                TournamentMatchView.of(bracket, entries));
    }
}
