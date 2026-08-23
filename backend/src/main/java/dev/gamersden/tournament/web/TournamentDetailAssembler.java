package dev.gamersden.tournament.web;

import dev.gamersden.tournament.domain.MatchExecutionService;
import dev.gamersden.tournament.domain.Tournament;
import dev.gamersden.tournament.domain.TournamentEntry;
import dev.gamersden.tournament.domain.TournamentEntryService;
import dev.gamersden.tournament.domain.TournamentService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds {@code GET /tournaments/{id}} — the card, the entries, the blocked consoles and the
 * bracket with every live countdown on it.
 *
 * <p>A bean of its own rather than a private method on {@link TournamentController} because §4.5
 * requires the SSE {@code tournament-update} payload to <em>equal</em> the GET shape, and the only
 * way to keep two things equal is to have one of them. {@code TournamentLiveEmitter} calls the
 * same assembler the controller does, so a change to the shape moves both at once.
 */
@Component
public class TournamentDetailAssembler {

    private final TournamentService tournaments;
    private final TournamentEntryService entries;
    private final MatchExecutionService matches;

    public TournamentDetailAssembler(TournamentService tournaments, TournamentEntryService entries,
                                     MatchExecutionService matches) {
        this.tournaments = tournaments;
        this.entries = entries;
        this.matches = matches;
    }

    public TournamentDetailView detail(long tournamentId) {
        return detail(tournaments.get(tournamentId));
    }

    public TournamentDetailView detail(Tournament tournament) {
        List<TournamentEntry> sold = entries.of(tournament.getId());
        return TournamentDetailView.of(tournament, sold,
                tournaments.stationIdsOf(tournament.getId()),
                matches.bracketOf(tournament.getId(), tournament.getMatchDurationMin()));
    }
}
