package dev.gamersden.tournament.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The four formulas of docs/tournaments.md §6, arithmetic only:
 *
 * <pre>
 *   revenue         = entries × entry_fee
 *   netProfit       = revenue − prize_pool
 *   opportunityCost = (N−1) × match_duration_min/60 × avgHourlyRate(allocated stations)
 *   extraMargin     = netProfit − opportunityCost
 * </pre>
 */
class TournamentFinanceTest {

    @Test
    @DisplayName("the worked example: a profitable event against its console-hours")
    void allFourNumbers() {
        // 8 players at 500, prize 1500, 7 matches of 20 min across two PS5 consoles at 120/hour.
        TournamentFinance finance = TournamentFinance.of(
                tournament(8, 500, 1500, 20), 8, List.of(120, 120));

        assertThat(finance.revenue()).isEqualTo(4000);
        assertThat(finance.netProfit()).isEqualTo(2500);
        assertThat(finance.matches()).isEqualTo(7);
        assertThat(finance.avgHourlyRate()).isEqualTo(120);
        // 7 × 20/60 × 120 = 280
        assertThat(finance.opportunityCost()).isEqualTo(280);
        assertThat(finance.extraMargin()).isEqualTo(2220);
        assertThat(finance.allocatedStations()).isEqualTo(2);
        assertThat(finance.verdict())
                .isEqualTo("This tournament generates ৳2,220 extra compared to standard hourly "
                        + "rentals");
    }

    @Test
    @DisplayName("an event that costs more than it earns says so")
    void negativeMargin() {
        // 4 players at 100 with a 1000 prize: 400 taken, 600 out of pocket before consoles.
        TournamentFinance finance = TournamentFinance.of(
                tournament(32, 100, 1000, 30), 4, List.of(120, 80));

        assertThat(finance.revenue()).isEqualTo(400);
        assertThat(finance.netProfit()).isEqualTo(-600);
        assertThat(finance.avgHourlyRate()).as("mean of the allocated console types").isEqualTo(100);
        // 31 × 30/60 × 100 = 1550
        assertThat(finance.opportunityCost()).isEqualTo(1550);
        assertThat(finance.extraMargin()).isEqualTo(-2150);
        assertThat(finance.verdict())
                .isEqualTo("This tournament earns ৳2,150 less than standard hourly rentals would "
                        + "have");
    }

    @Test
    @DisplayName("break-even gets its own line rather than a signed zero")
    void breakEven() {
        // 4 players at 100 = 400, no prize; 3 matches of 40 min on one 200/hour console = 400.
        TournamentFinance finance = TournamentFinance.of(
                tournament(4, 100, 0, 40), 4, List.of(200));

        assertThat(finance.netProfit()).isEqualTo(400);
        assertThat(finance.opportunityCost()).isEqualTo(400);
        assertThat(finance.extraMargin()).isZero();
        assertThat(finance.verdict())
                .isEqualTo("This tournament breaks even against standard hourly rentals");
    }

    @Test
    @DisplayName("N is the configured cap, not the turnout — the consoles were held either way")
    void opportunityCostFollowsTheCap() {
        TournamentFinance half = TournamentFinance.of(tournament(16, 200, 0, 20), 5, List.of(120));
        TournamentFinance full = TournamentFinance.of(tournament(16, 200, 0, 20), 16, List.of(120));

        assertThat(half.matches()).isEqualTo(15);
        assertThat(half.opportunityCost()).isEqualTo(full.opportunityCost()).isEqualTo(600);
        assertThat(half.revenue()).as("revenue does follow the turnout").isEqualTo(1000);
        assertThat(full.revenue()).isEqualTo(3200);
    }

    @Test
    @DisplayName("an event holding no consoles gives up nothing")
    void noAllocationNoOpportunityCost() {
        TournamentFinance finance = TournamentFinance.of(
                tournament(8, 300, 500, 20), 8, List.of());

        assertThat(finance.allocatedStations()).isZero();
        assertThat(finance.avgHourlyRate()).isZero();
        assertThat(finance.opportunityCost()).isZero();
        assertThat(finance.extraMargin()).isEqualTo(finance.netProfit()).isEqualTo(1900);
    }

    @Test
    @DisplayName("the division happens last, so short matches are not rounded away")
    void roundingKeepsPartHours() {
        // 3 × 20/60 × 125 = 125 exactly; an early integer divide would have produced 0.
        assertThat(TournamentFinance.opportunityCost(3, 20, 125)).isEqualTo(125);
        // 7 × 25/60 × 130 = 379.16… -> 379
        assertThat(TournamentFinance.opportunityCost(7, 25, 130)).isEqualTo(379);
        assertThat(TournamentFinance.opportunityCost(0, 20, 120)).isZero();
    }

    @Test
    @DisplayName("mixed console types average, rounded half-up")
    void averageRate() {
        assertThat(TournamentFinance.average(List.of())).isZero();
        assertThat(TournamentFinance.average(List.of(120, 80))).isEqualTo(100);
        assertThat(TournamentFinance.average(List.of(120, 80, 80))).isEqualTo(93);
    }

    @Test
    @DisplayName("a refunded ticket is money handed back, not revenue")
    void refundsAreNotRevenue() {
        TournamentFinance finance = TournamentFinance.of(tournament(8, 500, 0, 20), 0, List.of());

        assertThat(finance.entries()).isZero();
        assertThat(finance.revenue()).isZero();
    }

    private static Tournament tournament(int cap, int fee, int prize, int matchMinutes) {
        return new Tournament("Friday FIFA", "FIFA 25", Cadence.WEEKLY, OffsetDateTime.now(), fee,
                prize, cap, matchMinutes, 1L);
    }
}
