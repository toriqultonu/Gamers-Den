package dev.gamersden.tournament.domain;

import dev.gamersden.common.util.Money;

import java.util.List;

/**
 * What an event earned against what the consoles it tied up would have earned as ordinary rentals
 * (docs/tournaments.md §6). Manager/Admin only — the guard is on the controller, and the numbers
 * are never embedded in a payload a cashier can reach.
 *
 * <p>The four formulas are §6 verbatim:
 *
 * <pre>
 *   revenue         = entries × entry_fee
 *   netProfit       = revenue − prize_pool
 *   opportunityCost = (N−1) × match_duration_min/60 × avgHourlyRate(allocated stations)
 *   extraMargin     = netProfit − opportunityCost
 * </pre>
 *
 * <p>Two readings had to be pinned down, and both are pinned to the side that keeps the number
 * stable over the life of an event.
 *
 * <p><strong>{@code N} is the cap, not the turnout.</strong> The consoles were blocked for the
 * whole event from the moment a manager allocated them, so the rental income given up is the
 * income of the full bracket — {@code max_players − 1} matches of {@code match_duration_min}. An
 * undersubscribed event that draws a smaller bracket does not get a cheaper opportunity cost for
 * it, and the panel does not move the moment the draw lands.
 *
 * <p><strong>{@code entries} counts tickets still paid for.</strong> A refunded entry is money
 * handed back, and counting it as revenue would show a cancelled event earning what it returned.
 *
 * <p>{@code avgHourlyRate} is the plain rate-card hourly price of the allocated consoles' types —
 * no morning discount. It is a comparison, not a charge: nothing here is ever billed, and no
 * snapshot exists to read instead.
 */
public record TournamentFinance(int entries,
                                int entryFee,
                                int prizePool,
                                int matches,
                                int matchDurationMin,
                                int allocatedStations,
                                int avgHourlyRate,
                                int revenue,
                                int netProfit,
                                int opportunityCost,
                                int extraMargin,
                                String verdict) {

    public static TournamentFinance of(Tournament tournament, int paidEntries,
                                       List<Integer> allocatedHourlyRates) {
        int matches = Math.max(0, tournament.getMaxPlayers() - 1);
        int avgHourlyRate = average(allocatedHourlyRates);
        int revenue = paidEntries * tournament.getEntryFee();
        int netProfit = revenue - tournament.getPrizePool();
        int opportunityCost = opportunityCost(matches, tournament.getMatchDurationMin(),
                avgHourlyRate);
        int extraMargin = netProfit - opportunityCost;
        return new TournamentFinance(paidEntries, tournament.getEntryFee(),
                tournament.getPrizePool(), matches, tournament.getMatchDurationMin(),
                allocatedHourlyRates.size(), avgHourlyRate, revenue, netProfit, opportunityCost,
                extraMargin, verdictFor(extraMargin));
    }

    /**
     * Console-hours given up, in whole taka. The division is done last so a 20-minute match at
     * ৳120/hour is worth ৳40 rather than the ৳0 an early integer divide would produce.
     */
    static int opportunityCost(int matches, int matchDurationMin, int avgHourlyRate) {
        if (matches <= 0 || matchDurationMin <= 0 || avgHourlyRate <= 0) {
            return 0;
        }
        return Math.toIntExact(
                Math.round((long) matches * matchDurationMin * avgHourlyRate / 60.0));
    }

    /** Rounded half-up; an event holding no consoles gives up nothing. */
    static int average(List<Integer> rates) {
        if (rates.isEmpty()) {
            return 0;
        }
        long total = rates.stream().mapToLong(Integer::longValue).sum();
        return Math.toIntExact(Math.round(total / (double) rates.size()));
    }

    /** The line under the four stats in the manager rail (§6). */
    static String verdictFor(int extraMargin) {
        if (extraMargin > 0) {
            return "This tournament generates %s extra compared to standard hourly rentals"
                    .formatted(taka(extraMargin));
        }
        if (extraMargin < 0) {
            return "This tournament earns %s less than standard hourly rentals would have"
                    .formatted(taka(-extraMargin));
        }
        return "This tournament breaks even against standard hourly rentals";
    }

    private static String taka(int amount) {
        return Money.SYMBOL + String.format("%,d", amount);
    }
}
