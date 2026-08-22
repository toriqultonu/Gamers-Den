package dev.gamersden.tournament.domain;

import dev.gamersden.common.error.NotFoundException;
import dev.gamersden.common.spi.StationLookup;
import dev.gamersden.tournament.repo.TournamentEntryRepository;
import dev.gamersden.tournament.repo.TournamentRepository;
import dev.gamersden.tournament.repo.TournamentStationBlockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * {@code GET /tournaments/{id}/finance} — the manager rail's four stats and their verdict line
 * (docs/tournaments.md §6).
 *
 * <p>Its own bean rather than a method on {@link TournamentService} for one reason: these numbers
 * are Manager+ and must never be reachable from a shared payload. A separate service with a
 * separate endpoint and a separate guard means there is no assembly path along which they could
 * be folded into {@code GET /tournaments/{id}} by accident.
 *
 * <p>Nothing is stored. Every figure is recomputed from the event row, the entries still paid for
 * and the live rate card (invariant §5.4), so a rate edit moves the comparison the next time a
 * manager looks — which is what a comparison against <em>standard</em> hourly rentals should do.
 */
@Service
public class TournamentFinanceService {

    private final TournamentRepository tournaments;
    private final TournamentEntryRepository entries;
    private final TournamentStationBlockRepository blocks;
    private final StationLookup stations;

    public TournamentFinanceService(TournamentRepository tournaments,
                                    TournamentEntryRepository entries,
                                    TournamentStationBlockRepository blocks,
                                    StationLookup stations) {
        this.tournaments = tournaments;
        this.entries = entries;
        this.blocks = blocks;
        this.stations = stations;
    }

    @Transactional(readOnly = true)
    public TournamentFinance of(long tournamentId) {
        Tournament tournament = tournaments.findById(tournamentId)
                .orElseThrow(() -> new NotFoundException("Tournament", tournamentId));
        int paidEntries = (int) entries.findByTournamentIdOrderBySeedAsc(tournamentId).stream()
                .filter(entry -> !entry.isRefunded())
                .count();
        List<Integer> rates = blocks.findByIdTournamentIdOrderByIdStationIdAsc(tournamentId)
                .stream()
                .map(TournamentStationBlock::getStationId)
                .filter(stationId -> stations.find(stationId).isPresent())
                .map(stations::hourlyRate)
                .toList();
        return TournamentFinance.of(tournament, paidEntries, rates);
    }
}
