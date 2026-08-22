package dev.gamersden.tournament.web;

import dev.gamersden.tournament.domain.TournamentEntry;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * One sold ticket. The {@code qrToken} is deliberately absent: it is the ticket, it was returned
 * once to the terminal that sold it and printed on the stub, and a bracket read has no business
 * handing out something that opens the door.
 */
@Schema(name = "TournamentEntry")
public record TournamentEntryView(Long id,
                                  Long tournamentId,
                                  Long memberId,
                                  String playerName,
                                  @Schema(description = "Sale order; printed as TOKEN #NN") int seed,
                                  Long txId,
                                  boolean checkedIn,
                                  boolean refunded,
                                  OffsetDateTime createdAt) {

    public static TournamentEntryView of(TournamentEntry entry) {
        return new TournamentEntryView(entry.getId(), entry.getTournamentId(), entry.getMemberId(),
                entry.getPlayerName(), entry.getSeed(), entry.getTxId(), entry.isCheckedIn(),
                entry.isRefunded(), entry.getCreatedAt());
    }
}
