package dev.gamersden.tournament.domain;

import dev.gamersden.common.spi.TournamentBillLookup;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The {@code tournament} package's answer to {@link TournamentBillLookup}.
 *
 * <p>It answers "no entries", and after B12 that is the truth rather than a stub:
 * {@code tournament_entries.tx_id} is {@code NOT NULL}, so an entry cannot exist before the sale
 * that paid for it. There is no such thing as a registered-but-unsettled entry to put on a bill —
 * the fee reaches the money path as {@code tournamentEntries[]} on {@code POST /payments}, is
 * charged in the same transaction that writes the row, and lands on the receipt as a P5 stub.
 *
 * <p>The door stays because the bill's {@code tournamentDue} field, the {@code TOURNAMENT} line
 * kind and their place in {@code netTotal} are part of the FE contract, and because B13's bracket
 * work must not have to reintroduce a seam to add one.
 */
@Service
public class TournamentBillLookupService implements TournamentBillLookup {

    @Override
    public List<TournamentCharge> unbilledEntriesFor(long sessionId) {
        return List.of();
    }
}
