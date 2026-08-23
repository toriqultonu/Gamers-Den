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

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /reports} — S9's aggregates against a real Postgres, over four venue days of real
 * money (design.md S9, docs/bookings.md §6; TASKLIST B20).
 *
 * <p>The seed is deliberately awkward, because every awkward row is a rule:
 *
 * <ul>
 *   <li>a <b>refund</b> and a <b>void + reversal</b>, so the arithmetic has to prove it filters
 *       nothing and still nets out (invariant §5.7);</li>
 *   <li>a bill part-paid in <b>loyalty points</b>, so the gross buckets and the tendered revenue
 *       cannot be the same number;</li>
 *   <li>a station <b>under maintenance</b> that earned nothing, so utilisation has to keep a row
 *       for a console that never opened;</li>
 *   <li>a <b>live session</b> and an <b>open till</b>, so both have to be clipped at "now" rather
 *       than run to the end of the day;</li>
 *   <li>bookings that were <b>used</b>, <b>cancelled</b>, <b>expired</b>, <b>checked in</b> and
 *       <b>still in the future</b>, which is the whole of the show-rate's definition.</li>
 * </ul>
 *
 * @see ReportsAccessIT for the permission matrix
 */
@Import(MutableClockConfig.class)
class ReportsIT extends AbstractApiIntegrationTest {

    @Autowired
    private MutableClock clock;

    private ReportFixtures seed;

    @BeforeEach
    void fourDaysOfTrading() {
        seed = new ReportFixtures(jdbc, clock, adminId);
        seed.write();
    }

    // ---- the KPI row ----------------------------------------------------------------------

    @Test
    @DisplayName("KPIs fold every bucket over the range, refunds and voids included")
    void kpisOverTheRange() {
        JsonNode kpis = report().get("kpis");

        // 800 + 400 + 500 + 420 + 500 - 300 + 100 - 100.
        assertThat(kpis.get("revenue").asInt()).isEqualTo(2320);
        assertThat(kpis.get("gaming").asInt()).isEqualTo(740);
        assertThat(kpis.get("fnb").asInt()).isEqualTo(500);
        assertThat(kpis.get("tournament").asInt()).isEqualTo(600);
        assertThat(kpis.get("booking").asInt()).isEqualTo(500);
        assertThat(kpis.get("pointsRedeemed").asInt()).isEqualTo(20);
        // The buckets are gross, revenue is what was tendered: they differ by the points discount.
        assertThat(kpis.get("gaming").asInt() + kpis.get("fnb").asInt()
                + kpis.get("tournament").asInt() + kpis.get("booking").asInt())
                .isEqualTo(kpis.get("revenue").asInt() + kpis.get("pointsRedeemed").asInt());

        assertThat(kpis.get("expenses").asInt()).isEqualTo(500);
        assertThat(kpis.get("netProfit").asInt()).isEqualTo(1820);

        // Eight postings, six of which took money in; the two negatives are not sales.
        assertThat(kpis.get("transactions").asInt()).isEqualTo(8);
        assertThat(kpis.get("sales").asInt()).isEqualTo(6);
        assertThat(kpis.get("avgTicket").asInt()).isEqualTo(387);
        assertThat(kpis.get("sessions").asInt()).isEqualTo(4);
    }

    @Test
    @DisplayName("the trend has one bar per venue day, quiet days zero-filled and in order")
    void trendIsZeroFilledAndOrdered() {
        JsonNode trend = report().get("trend");

        assertThat(trend).hasSize(4);
        assertThat(trend.get(0).get("date").asText()).isEqualTo(seed.day(-3).toString());
        assertThat(trend.get(3).get("date").asText()).isEqualTo(seed.day(0).toString());

        // Nothing happened three days ago — a bar of height zero, not a missing bar.
        assertThat(trend.get(0).get("revenue").asInt()).isZero();
        assertThat(trend.get(0).get("transactions").asInt()).isZero();

        JsonNode twoDaysAgo = trend.get(1);
        assertThat(twoDaysAgo.get("revenue").asInt()).isEqualTo(1200);
        assertThat(twoDaysAgo.get("gaming").asInt()).isEqualTo(500);
        assertThat(twoDaysAgo.get("fnb").asInt()).isEqualTo(300);
        assertThat(twoDaysAgo.get("tournament").asInt()).isEqualTo(400);
        assertThat(twoDaysAgo.get("expenses").asInt()).isEqualTo(300);
        assertThat(twoDaysAgo.get("netProfit").asInt()).isEqualTo(900);

        // Yesterday is the pre-booking sale and nothing else.
        assertThat(trend.get(2).get("booking").asInt()).isEqualTo(500);
        assertThat(trend.get(2).get("revenue").asInt()).isEqualTo(500);

        // Today: the booking sale and its refund cancel out inside the booking bucket.
        JsonNode today = trend.get(3);
        assertThat(today.get("booking").asInt()).isZero();
        assertThat(today.get("revenue").asInt()).isEqualTo(620);
        assertThat(today.get("expenses").asInt()).isEqualTo(200);
        assertThat(today.get("netProfit").asInt()).isEqualTo(420);

        assertThat(sumOf(trend, "revenue")).isEqualTo(2320);
        assertThat(sumOf(trend, "expenses")).isEqualTo(500);
    }

    // ---- time ------------------------------------------------------------------------------

    @Test
    @DisplayName("utilisation is a share of the hours a till was open, not of the wall clock")
    void stationUtilisation() {
        JsonNode report = report();

        // Two closed shifts (10 h and 8 h) and one still open, clipped at 22:00 rather than run to
        // midnight: 36000 + 28800 + 43200.
        assertThat(report.get("tradingSeconds").asLong()).isEqualTo(108_000);

        List<JsonNode> stations = List.of(report.get("stationUtilisation").get(0),
                report.get("stationUtilisation").get(1),
                report.get("stationUtilisation").get(2));
        assertThat(stations).hasSize(3);

        JsonNode ps4 = stations.get(0);
        assertThat(ps4.get("name").asText()).isEqualTo("PS4-01");
        assertThat(ps4.get("underMaintenance").asBoolean()).isTrue();
        assertThat(ps4.get("busySeconds").asLong()).isZero();
        assertThat(ps4.get("utilisationPct").asDouble()).isZero();

        // Three hours across two visits, out of thirty hours of trading.
        JsonNode first = stations.get(1);
        assertThat(first.get("name").asText()).isEqualTo("PS5-01");
        assertThat(first.get("sessions").asInt()).isEqualTo(2);
        assertThat(first.get("busySeconds").asLong()).isEqualTo(10_800);
        assertThat(first.get("utilisationPct").asDouble()).isEqualTo(10.0);

        // One closed hour plus a live seat clipped at now — not run on to midnight.
        JsonNode second = stations.get(2);
        assertThat(second.get("name").asText()).isEqualTo("PS5-02");
        assertThat(second.get("busySeconds").asLong()).isEqualTo(7_200);
        assertThat(second.get("utilisationPct").asDouble()).isEqualTo(6.7);
    }

    @Test
    @DisplayName("busiest hours split occupancy and takings by the venue hour they happened in")
    void busiestHours() {
        JsonNode hours = report().get("busiestHours");

        assertThat(hours).hasSize(24);
        assertThat(hours.get(0).get("hour").asInt()).isZero();
        assertThat(hours.get(23).get("hour").asInt()).isEqualTo(23);

        // 13:00 took the first sale and held a seat for the whole hour.
        assertThat(hours.get(13).get("revenue").asInt()).isEqualTo(800);
        assertThat(hours.get(13).get("busySeconds").asLong()).isEqualTo(3_600);
        assertThat(hours.get(13).get("avgStationsBusy").asDouble()).isEqualTo(0.3);

        // 19:00 took a 400 entry sale and gave back 300 — the hour nets to 100, and both count.
        assertThat(hours.get(19).get("revenue").asInt()).isEqualTo(100);
        assertThat(hours.get(19).get("sales").asInt()).isEqualTo(1);

        // 20:00 is the void and its reversal: two postings, one sale, nothing kept.
        assertThat(hours.get(20).get("revenue").asInt()).isZero();
        assertThat(hours.get(20).get("sales").asInt()).isEqualTo(1);

        // 03:00 has never had a customer.
        assertThat(hours.get(3).get("revenue").asInt()).isZero();
        assertThat(hours.get(3).get("busySeconds").asLong()).isZero();

        assertThat(sumOf(hours, "revenue")).isEqualTo(2320);
        assertThat(hours.get(21).get("busySeconds").asLong()).isEqualTo(3_600);
    }

    // ---- what sold -------------------------------------------------------------------------

    @Test
    @DisplayName("top sellers price the carts a sale that stuck settled, and skip the voided one")
    void topSellers() {
        JsonNode sellers = report().get("topSellers");

        assertThat(sellers).hasSize(2);
        assertThat(sellers.get(0).get("name").asText()).isEqualTo("Pepsi 250ml");
        assertThat(sellers.get(0).get("units").asInt()).isEqualTo(5);
        assertThat(sellers.get(0).get("revenue").asInt()).isEqualTo(300);
        assertThat(sellers.get(1).get("name").asText()).isEqualTo("Chips");
        assertThat(sellers.get(1).get("units").asInt()).isEqualTo(5);
        assertThat(sellers.get(1).get("revenue").asInt()).isEqualTo(200);

        // The five bottles of water were on the cart that was voided: nothing left the counter.
        assertThat(sellers.toString()).doesNotContain("Water");
    }

    // ---- bookings --------------------------------------------------------------------------

    @Test
    @DisplayName("bookings per day, the show-rate and package-fee income (bookings.md 6)")
    void bookingAggregates() {
        JsonNode bookings = report().get("bookings");

        assertThat(bookings.get("booked").asInt()).isEqualTo(6);
        assertThat(bookings.get("used").asInt()).isEqualTo(2);
        assertThat(bookings.get("cancelled").asInt()).isEqualTo(1);
        assertThat(bookings.get("arrived").asInt()).isEqualTo(1);
        // Paid, midday slot already gone, never checked in. The 23:00 slot is still to come.
        assertThat(bookings.get("expired").asInt()).isEqualTo(1);
        // 2 / (2 + 1 + 1).
        assertThat(bookings.get("showRatePct").asDouble()).isEqualTo(50.0);

        // Income is keyed on the day the money was taken; the cancelled one was refunded in full.
        assertThat(bookings.get("sold").asInt()).isEqualTo(5);
        assertThat(bookings.get("playIncome").asInt()).isEqualTo(2000);
        assertThat(bookings.get("packageFeeIncome").asInt()).isEqualTo(500);
        assertThat(bookings.get("income").asInt()).isEqualTo(2500);

        // Attendance is keyed on the slot's own day instead.
        JsonNode perDay = bookings.get("perDay");
        assertThat(perDay).hasSize(3);
        assertThat(perDay.get(0).get("date").asText()).isEqualTo(seed.day(-2).toString());
        assertThat(perDay.get(0).get("used").asInt()).isEqualTo(1);
        assertThat(perDay.get(1).get("booked").asInt()).isEqualTo(2);
        assertThat(perDay.get(1).get("cancelled").asInt()).isEqualTo(1);
        assertThat(perDay.get(2).get("date").asText()).isEqualTo(seed.day(0).toString());
        assertThat(perDay.get(2).get("booked").asInt()).isEqualTo(3);
        assertThat(perDay.get(2).get("arrived").asInt()).isEqualTo(1);
        assertThat(perDay.get(2).get("expired").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("a range nothing has resolved in reports no show-rate rather than a zero one")
    void showRateIsAbsentWithoutData() {
        JsonNode bookings = reportOver(seed.day(-13), seed.day(-4)).get("bookings");

        assertThat(bookings.get("booked").asInt()).isZero();
        assertThat(bookings.has("showRatePct")).isFalse();
    }

    // ---- the range itself ------------------------------------------------------------------

    @Test
    @DisplayName("an unqualified request answers the last fourteen venue days")
    void defaultRange() {
        JsonNode range = ok(get("/api/v1/reports", adminBearer())).get("range");

        assertThat(range.get("days").asInt()).isEqualTo(14);
        assertThat(range.get("to").asText()).isEqualTo(seed.day(0).toString());
        assertThat(range.get("from").asText()).isEqualTo(seed.day(-13).toString());
    }

    @Test
    @DisplayName("a range that ends before it starts is a 400, not an empty report")
    void backwardsRange() {
        ResponseEntity<JsonNode> response = get(
                "/api/v1/reports?from=" + seed.day(0) + "&to=" + seed.day(-3), adminBearer());

        assertErrorEnvelope(response, 400, "VALIDATION_FAILED");
    }

    // ---- helpers ---------------------------------------------------------------------------

    private JsonNode report() {
        return reportOver(seed.day(-3), seed.day(0));
    }

    private JsonNode reportOver(LocalDate from, LocalDate to) {
        return ok(get("/api/v1/reports?from=" + from + "&to=" + to, adminBearer()));
    }

    private static JsonNode ok(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode().value())
                .as("body %s", response.getBody())
                .isEqualTo(200);
        return response.getBody();
    }

    private static int sumOf(JsonNode rows, String field) {
        int total = 0;
        for (JsonNode row : rows) {
            total += row.get(field).asInt();
        }
        return total;
    }
}
