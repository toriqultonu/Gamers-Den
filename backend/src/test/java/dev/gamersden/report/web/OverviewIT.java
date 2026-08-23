package dev.gamersden.report.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamersden.support.AbstractApiIntegrationTest;
import dev.gamersden.support.MutableClock;
import dev.gamersden.support.MutableClockConfig;
import dev.gamersden.support.ReportFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /overview} — S2's tiles against a real Postgres, over the same four seeded days as
 * {@link ReportsIT} (design.md S2, docs/bookings.md §6; TASKLIST B20).
 *
 * <p>What this suite is really about is the difference between "today", "now" and "the last 30
 * days", which S2 shows side by side and which are three different questions: the KPI tiles are
 * the venue day, occupancy is this instant, and the trends are a window that has to be zero-filled
 * before it can be a chart.
 */
@Import(MutableClockConfig.class)
class OverviewIT extends AbstractApiIntegrationTest {

    @Autowired
    private MutableClock clock;

    private ReportFixtures seed;

    @BeforeEach
    void fourDaysOfTrading() {
        seed = new ReportFixtures(jdbc, clock, adminId);
        seed.write();
    }

    @Test
    @DisplayName("the KPI tiles are the venue day, and occupancy is this instant")
    void todaysTiles() {
        JsonNode overview = overview();

        assertThat(overview.get("date").asText()).isEqualTo(ReportFixtures.TODAY.toString());
        assertThat(overview.get("serverTime").asText()).isNotBlank();

        // One live seat; the PS4 is in pieces and so is not one of the seats that could be busy.
        JsonNode occupancy = overview.get("occupancy");
        assertThat(occupancy.get("busy").asInt()).isEqualTo(1);
        assertThat(occupancy.get("stations").asInt()).isEqualTo(3);
        assertThat(occupancy.get("maintenance").asInt()).isEqualTo(1);
        assertThat(occupancy.get("available").asInt()).isEqualTo(2);
        assertThat(occupancy.get("pct").asDouble()).isEqualTo(50.0);

        // 420 + 500 - 300 + 100 - 100, against 200 of petty cash.
        JsonNode today = overview.get("today");
        assertThat(today.get("revenue").asInt()).isEqualTo(620);
        assertThat(today.get("gaming").asInt()).isEqualTo(240);
        assertThat(today.get("fnb").asInt()).isEqualTo(200);
        assertThat(today.get("tournament").asInt()).isEqualTo(200);
        assertThat(today.get("booking").asInt()).isZero();
        assertThat(today.get("expenses").asInt()).isEqualTo(200);
        assertThat(today.get("netProfit").asInt()).isEqualTo(420);
        assertThat(today.get("transactions").asInt()).isEqualTo(5);
        assertThat(today.get("sales").asInt()).isEqualTo(3);
        assertThat(today.get("avgTicket").asInt()).isEqualTo(207);
        assertThat(today.get("sessions").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("pre-sold is PAID bookings plus WAITING play tickets, and nothing counted twice")
    void preSoldStat() {
        JsonNode preSold = overview().get("preSold");

        // Two bookings still PAID: 200 + 600 of play time and 100 + 100 of package fee.
        assertThat(preSold.get("bookings").asInt()).isEqualTo(2);
        assertThat(preSold.get("bookingPlayAmount").asInt()).isEqualTo(800);
        assertThat(preSold.get("bookingPackageFee").asInt()).isEqualTo(200);
        assertThat(preSold.get("bookingAmount").asInt()).isEqualTo(1000);

        // One walk-up token still waiting. The seated one is delivered, and the checked-in
        // booking's token is a BOOKING token — counting it here would bill it twice.
        assertThat(preSold.get("playTickets").asInt()).isEqualTo(1);
        assertThat(preSold.get("playTicketAmount").asInt()).isEqualTo(240);

        assertThat(preSold.get("amount").asInt()).isEqualTo(1240);
    }

    @Test
    @DisplayName("the 30-day trend is zero-filled, and the 30 before it are the comparison")
    void trends() {
        JsonNode trend = overview().get("revenue30Days");

        assertThat(trend.get("days")).hasSize(30);
        assertThat(trend.get("days").get(29).get("date").asText())
                .isEqualTo(ReportFixtures.TODAY.toString());
        assertThat(trend.get("days").get(29).get("revenue").asInt()).isEqualTo(620);
        assertThat(trend.get("days").get(0).get("revenue").asInt()).isZero();
        assertThat(trend.get("revenue").asInt()).isEqualTo(2320);
        // The venue had not opened yet in the window before this one.
        assertThat(trend.get("previousRevenue").asInt()).isZero();

        JsonNode weekdays = overview().get("byDayOfWeek");
        assertThat(weekdays).hasSize(7);
        int total = 0;
        for (JsonNode weekday : weekdays) {
            total += weekday.get("revenue").asInt();
            // 30 days is four of some weekdays and five of others; each average uses its own count.
            assertThat(weekday.get("days").asInt()).isBetween(4, 5);
        }
        assertThat(total).isEqualTo(2320);

        JsonNode wednesday = weekdayNamed(weekdays, ReportFixtures.TODAY.getDayOfWeek().name());
        assertThat(wednesday.get("revenue").asInt()).isEqualTo(620);
        assertThat(wednesday.get("average").asInt())
                .isEqualTo(Math.round(620f / wednesday.get("days").asInt()));
    }

    @Test
    @DisplayName("the watchlist is what is at or below its reorder point, deepest shortfall first")
    void stockWatchlist() {
        JsonNode watchlist = overview().get("stockWatchlist");

        assertThat(watchlist).hasSize(2);
        assertThat(watchlist.get(0).get("name").asText()).isEqualTo("Pepsi 250ml");
        assertThat(watchlist.get(0).get("stock").asInt()).isEqualTo(4);
        assertThat(watchlist.get(0).get("reorderAt").asInt()).isEqualTo(10);
        assertThat(watchlist.get(1).get("name").asText()).isEqualTo("Water 500ml");
        // 50 in stock against a reorder point of 5 is not a worry.
        assertThat(watchlist.toString()).doesNotContain("Chips");
    }

    @Test
    @DisplayName("recent closes carry the shift's whole takings beside its cash discrepancy")
    void recentCloses() {
        JsonNode closes = overview().get("recentCloses");

        // Newest close first; the till still open is not a close.
        assertThat(closes).hasSize(2);

        JsonNode yesterday = closes.get(0);
        assertThat(yesterday.get("shiftId").asLong()).isEqualTo(seed.closedShiftB);
        assertThat(yesterday.get("takings").asInt()).isEqualTo(500);
        assertThat(yesterday.get("discrepancy").asInt()).isEqualTo(-100);
        assertThat(yesterday.get("terminal").asText()).isEqualTo(TERMINAL);
        assertThat(yesterday.get("closedAt").asText()).isNotBlank();

        JsonNode twoDaysAgo = closes.get(1);
        assertThat(twoDaysAgo.get("shiftId").asLong()).isEqualTo(seed.closedShiftA);
        assertThat(twoDaysAgo.get("takings").asInt()).isEqualTo(1200);
        assertThat(twoDaysAgo.get("discrepancy").asInt()).isEqualTo(200);
        assertThat(twoDaysAgo.get("openingFloat").asInt()).isEqualTo(2000);
    }

    private JsonNode overview() {
        ResponseEntity<JsonNode> response = get("/api/v1/overview", adminBearer());
        assertThat(response.getStatusCode().value())
                .as("body %s", response.getBody())
                .isEqualTo(200);
        return response.getBody();
    }

    private static JsonNode weekdayNamed(JsonNode weekdays, String name) {
        for (JsonNode weekday : weekdays) {
            if (weekday.get("day").asText().equals(name)) {
                return weekday;
            }
        }
        throw new AssertionError("no weekday row for " + name);
    }
}
