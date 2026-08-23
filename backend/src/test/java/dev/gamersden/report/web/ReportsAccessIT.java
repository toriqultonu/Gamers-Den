package dev.gamersden.report.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamersden.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The permission matrix for the two report screens (api-contract.md §1: "Reports, Overview — Admin
 * yes, Manager reports only, Cashier no"; ARCHITECTURE.md §4.6).
 *
 * <p>S9 hides itself from a cashier and S2 redirects a non-admin to the Floor, but both of those
 * are cosmetic. This is where the rule is actually kept, and the three roles are asserted against
 * both endpoints so a manager's half-permission cannot quietly become a whole one.
 */
class ReportsAccessIT extends AbstractApiIntegrationTest {

    private static final String MANAGER_PIN = "1111";
    private static final String CASHIER_PIN = "4321";

    private HttpHeaders manager;
    private HttpHeaders cashier;

    @BeforeEach
    void twoMoreOperators() {
        manager = bearerFor(createStaff("Nusrat", "MANAGER", MANAGER_PIN), MANAGER_PIN);
        cashier = bearerFor(createStaff("Rafi", "CASHIER", CASHIER_PIN), CASHIER_PIN);
    }

    @Test
    @DisplayName("a cashier is refused both screens with the error envelope")
    void cashierIsRefusedBoth() {
        assertErrorEnvelope(get("/api/v1/reports", cashier), 403, "FORBIDDEN");
        assertErrorEnvelope(get("/api/v1/overview", cashier), 403, "FORBIDDEN");
    }

    @Test
    @DisplayName("a manager gets reports but not the Overview")
    void managerGetsReportsOnly() {
        assertThat(get("/api/v1/reports", manager).getStatusCode().value()).isEqualTo(200);
        assertErrorEnvelope(get("/api/v1/overview", manager), 403, "FORBIDDEN");
    }

    @Test
    @DisplayName("an admin gets both, and an empty venue answers zeroes rather than failing")
    void adminGetsBothOnAnEmptyVenue() {
        HttpHeaders admin = adminBearer();

        JsonNode report = get("/api/v1/reports", admin).getBody();
        assertThat(report.get("kpis").get("revenue").asInt()).isZero();
        assertThat(report.get("trend")).hasSize(14);
        assertThat(report.get("stationUtilisation")).isEmpty();
        assertThat(report.get("busiestHours")).hasSize(24);
        assertThat(report.get("topSellers")).isEmpty();
        assertThat(report.get("tradingSeconds").asLong()).isZero();
        assertThat(report.get("bookings").has("showRatePct")).isFalse();

        JsonNode overview = get("/api/v1/overview", admin).getBody();
        assertThat(overview.get("occupancy").get("pct").asDouble()).isZero();
        assertThat(overview.get("preSold").get("amount").asInt()).isZero();
        assertThat(overview.get("revenue30Days").get("days")).hasSize(30);
        assertThat(overview.get("byDayOfWeek")).hasSize(7);
        assertThat(overview.get("recentCloses")).isEmpty();
    }

    @Test
    @DisplayName("no token at all is a 401, not a 403")
    void anonymousIsUnauthorised() {
        assertErrorEnvelope(get("/api/v1/reports", null), 401, "UNAUTHORIZED");
        assertErrorEnvelope(get("/api/v1/overview", null), 401, "UNAUTHORIZED");
    }

    private Long createStaff(String name, String role, String pin) {
        return post("/api/v1/staff", Map.of("name", name, "role", role, "pin", pin), adminBearer())
                .getBody().get("id").asLong();
    }
}
