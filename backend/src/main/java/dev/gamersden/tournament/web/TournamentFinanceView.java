package dev.gamersden.tournament.web;

import dev.gamersden.tournament.domain.TournamentFinance;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * {@code GET /tournaments/{id}/finance} — the four stats and the verdict line in the manager rail
 * (docs/tournaments.md §6). Manager+ only; a cashier token gets the 403 envelope, and these
 * numbers appear in no other payload.
 *
 * <p>The inputs ship with the answers so the panel can show its working — an operator asking
 * "compared to what?" gets {@code matches x matchDurationMin} console-minutes at
 * {@code avgHourlyRate} rather than an unexplained number.
 */
@Schema(name = "TournamentFinance")
public record TournamentFinanceView(
        @Schema(description = "Tickets still paid for; a refunded entry is not revenue")
        int entries,
        int entryFee,
        int prizePool,
        @Schema(description = "N-1 for the configured cap — the consoles were held for the whole "
                + "event, whatever the turnout")
        int matches,
        int matchDurationMin,
        int allocatedStations,
        @Schema(description = "Mean rate-card hourly price of the allocated consoles' types")
        int avgHourlyRate,
        @Schema(description = "entries x entryFee") int revenue,
        @Schema(description = "revenue - prizePool") int netProfit,
        @Schema(description = "matches x matchDurationMin/60 x avgHourlyRate") int opportunityCost,
        @Schema(description = "netProfit - opportunityCost") int extraMargin,
        String verdict) {

    public static TournamentFinanceView of(TournamentFinance finance) {
        return new TournamentFinanceView(finance.entries(), finance.entryFee(),
                finance.prizePool(), finance.matches(), finance.matchDurationMin(),
                finance.allocatedStations(), finance.avgHourlyRate(), finance.revenue(),
                finance.netProfit(), finance.opportunityCost(), finance.extraMargin(),
                finance.verdict());
    }
}
