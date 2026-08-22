package dev.gamersden.tournament.web;

import dev.gamersden.tournament.domain.Tournament;
import dev.gamersden.tournament.domain.TournamentEntry;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * {@code GET /tournaments/{id}} — the card plus who is in it and which consoles it holds.
 *
 * <p>The bracket rides along in this same payload from B13; the field is not shaped here because
 * the match rows do not exist yet and inventing their JSON now would only lock the FE to a guess.
 */
@Schema(name = "TournamentDetail")
public record TournamentDetailView(TournamentView tournament,
                                   List<TournamentEntryView> entries,
                                   @Schema(description = "Consoles blocked for this event")
                                   List<Long> stationIds) {

    public static TournamentDetailView of(Tournament tournament, List<TournamentEntry> entries,
                                          List<Long> stationIds) {
        return new TournamentDetailView(TournamentView.of(tournament, entries.size()),
                entries.stream().map(TournamentEntryView::of).toList(), stationIds);
    }
}
