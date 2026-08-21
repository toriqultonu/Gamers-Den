package dev.gamersden.common.spi;

import java.util.List;

/**
 * The narrow read the {@code billing} package needs from {@code tournament} — the entry fees that
 * belong on a session's bill (api-contract.md, {@code GET /sessions/{id}/bill}: "tournament
 * lines").
 *
 * <p>Implemented by {@code tournament/domain/TournamentBillLookupService}, which answers "no
 * entries" until B12 brings {@code tournaments} and {@code tournament_entries}. The line kind, the
 * totals field and the JSON shape are already live, so B12 only has to make this method tell the
 * truth — the FE's bill panel contract does not move.
 */
public interface TournamentBillLookup {

    /** Registered-but-unsettled entry fees hanging off this session, oldest first. */
    List<TournamentCharge> unbilledEntriesFor(long sessionId);

    /**
     * One entry fee.
     *
     * @param entryId    the {@code tournament_entries} row the fee belongs to
     * @param playerName the name printed on the seed stub, when one was given
     * @param fee        the entry fee snapshot, in BDT
     */
    record TournamentCharge(long entryId,
                            long tournamentId,
                            String tournamentName,
                            String playerName,
                            int fee) {
    }
}
