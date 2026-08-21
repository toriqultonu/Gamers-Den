package dev.gamersden.tournament.domain;

import dev.gamersden.common.spi.TournamentBillLookup;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The {@code tournament} package's answer to {@link TournamentBillLookup}. Nothing can be
 * registered yet — {@code tournaments} and {@code tournament_entries} land in V002 with B12 — so
 * this says "no entries" and the bill's tournament section stays empty.
 *
 * <p>The line kind, the {@code tournamentDue} total and their place in {@code netTotal} are
 * already live, so B12 replaces the body with a query and no caller, and no FE contract, changes.
 */
@Service
public class TournamentBillLookupService implements TournamentBillLookup {

    @Override
    public List<TournamentCharge> unbilledEntriesFor(long sessionId) {
        return List.of();
    }
}
