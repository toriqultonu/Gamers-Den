package dev.gamersden.shift.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamersden.support.AbstractApiIntegrationTest;
import dev.gamersden.support.FloorFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /shifts/current/x-report} against a real Postgres, over real money.
 *
 * <p>{@link dev.gamersden.shift.domain.ShiftTakingsTest} owns the arithmetic; what is proved here
 * is that the arithmetic is being fed the right rows — that "takings" really means every
 * {@code payment_splits} row of every transaction posted to the shift, that a void's negative
 * transaction subtracts itself without anyone filtering for it, and that expected cash is the
 * float plus the cash drawer movements minus the petty cash that came back out of it.
 *
 * <p>Every figure here is derived at read time and none of it is stored while the shift is open
 * (invariant §5.4) — only the close snapshots it.
 */
class ShiftXReportIT extends AbstractApiIntegrationTest {

    private static final int PS5_HALF_HOUR = 80;
    private static final int PEPSI = 60;
    private static final int FLOAT = 2000;

    private FloorFixtures floor;
    private HttpHeaders staff;
    private Long stationId;
    private Long shiftId;

    @BeforeEach
    void openTheTill() {
        floor = new FloorFixtures(jdbc);
        staff = adminBearer();
        stationId = createStation("PS5-01", "PS5");
        shiftId = openShift(FLOAT);
    }

    @Test
    @DisplayName("a shift that has taken nothing reports the float and a row per method")
    void anUntouchedShift() {
        JsonNode report = xReport();

        assertThat(report.get("kind").asText()).isEqualTo("X");
        assertThat(report.get("shiftId").asLong()).isEqualTo(shiftId);
        assertThat(report.get("terminal").asText()).isEqualTo(TERMINAL);
        assertThat(report.get("openingFloat").asInt()).isEqualTo(FLOAT);
        assertThat(report.get("serverTime").asText()).isNotBlank();
        assertThat(methods(report)).containsExactly("CASH", "BKASH", "NAGAD", "WALLET");
        assertThat(report.get("takings").get("totals").get("total").asInt()).isZero();
        assertThat(report.get("cash").get("expected").asInt()).isEqualTo(FLOAT);
        // Nobody has counted the drawer yet, so those fields are absent rather than null.
        assertThat(report.get("cash").has("counted")).isFalse();
        assertThat(report.get("cash").has("discrepancy")).isFalse();
        assertThat(report.has("printJobId")).isFalse();
    }

    @Test
    @DisplayName("mixed tenders, a refund and petty cash all land in the expected-cash line")
    void expectedCashFromMixedMethodsRefundsAndExpenses() {
        // A seat: 2 blocks of gaming at 80 and two drinks at 60 = 280, half in cash, half by bKash.
        Long sessionId = floor.runningSessionOn(stationId, shiftId, 2, PS5_HALF_HOUR, 0);
        Long itemId = createItem("Pepsi 250ml", PEPSI, 100);
        cartOn(sessionId, itemId, 2);
        settle(Map.of("target", Map.of("sessionId", sessionId),
                "splits", List.of(Map.of("method", "CASH", "amount", 100),
                        Map.of("method", "BKASH", "amount", 180, "paymentRef", "8XK21QW7"))));

        // A counter sale in cash, then voided — the reversal is a negative cash row (invariant §5.7).
        long counterTx = settle(Map.of("target", Map.of("cartId", counterCart(itemId, 1)),
                "splits", List.of(Map.of("method", "CASH", "amount", PEPSI))))
                .get("transactionId").asLong();
        voidPayment(counterTx);

        expense("Cleaning supplies", "SUPPLIES", 300);
        expense("Bus fare", "OTHER", 100);

        JsonNode report = xReport();
        JsonNode takings = report.get("takings");

        // The 100 cash and the 180 bKash are each attributed to what they bought, in proportion;
        // the counter sale and its reversal cancel out, cell by cell.
        assertThat(row(takings, "CASH")).isEqualTo(Map.of("gaming", 57, "fnb", 43,
                "tournament", 0, "booking", 0, "total", 100));
        assertThat(row(takings, "BKASH")).isEqualTo(Map.of("gaming", 103, "fnb", 77,
                "tournament", 0, "booking", 0, "total", 180));
        assertThat(row(takings, "NAGAD").get("total")).isEqualTo(0);
        assertThat(row(takings, "WALLET").get("total")).isEqualTo(0);
        assertThat(row(takings, null)).isEqualTo(Map.of("gaming", 160, "fnb", 120,
                "tournament", 0, "booking", 0, "total", 280));

        // The seat and the counter sale took money; the void gave it back.
        assertThat(takings.get("saleCount").asInt()).isEqualTo(2);
        assertThat(takings.get("refundCount").asInt()).isEqualTo(1);

        JsonNode expenses = report.get("expenses");
        assertThat(expenses.get("total").asInt()).isEqualTo(400);
        assertThat(expenses.get("count").asInt()).isEqualTo(2);
        assertThat(expenses.get("lines")).hasSize(2);

        // 2000 float + 100 cash taken (160 in, 60 refunded back out) - 400 petty cash.
        JsonNode cash = report.get("cash");
        assertThat(cash.get("openingFloat").asInt()).isEqualTo(FLOAT);
        assertThat(cash.get("takings").asInt()).isEqualTo(100);
        assertThat(cash.get("expenses").asInt()).isEqualTo(400);
        assertThat(cash.get("expected").asInt()).isEqualTo(1700);

        // Derived, never stored: the open shift row still holds nothing but its float (§5.4).
        Map<String, Object> shift = jdbc.queryForMap(
                "SELECT expected_cash, counted_cash, discrepancy FROM shifts WHERE id = ?", shiftId);
        assertThat(shift.get("expected_cash")).isNull();
        assertThat(shift.get("counted_cash")).isNull();
        assertThat(shift.get("discrepancy")).isNull();
    }

    @Test
    @DisplayName("a points discount is absorbed by the categories, and reported on its own line")
    void pointsAreReportedApartFromTheCategories() {
        Long memberId = createMember("Rifat Hasan", "+8801712448190", 500);
        Long sessionId = floor.runningSessionOn(stationId, shiftId, 2, PS5_HALF_HOUR, 0);
        jdbc.update("UPDATE sessions SET member_id = ? WHERE id = ?", memberId, sessionId);

        // 160 of gaming, 100 of it paid with points: only 60 reaches the drawer.
        settle(Map.of("target", Map.of("sessionId", sessionId), "redeemPoints", 100,
                "splits", List.of(Map.of("method", "CASH", "amount", 60))));

        JsonNode takings = xReport().get("takings");

        assertThat(row(takings, "CASH")).isEqualTo(Map.of("gaming", 60, "fnb", 0,
                "tournament", 0, "booking", 0, "total", 60));
        assertThat(takings.get("pointsRedeemed").asInt()).isEqualTo(100);
        assertThat(takings.get("pointsEarned").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("print=true queues the P3 job for this shift")
    void printQueuesTheInterimReport() {
        JsonNode report = get("/api/v1/shifts/current/x-report?print=true", staff).getBody();

        long printJobId = report.get("printJobId").asLong();
        Map<String, Object> job = jdbc.queryForMap(
                "SELECT type, ref_id, status, device_id, operator_id, rendered_text "
                        + "FROM print_jobs WHERE id = ?", printJobId);
        assertThat(job).containsEntry("type", "X_REPORT")
                .containsEntry("ref_id", shiftId)
                .containsEntry("status", "QUEUED")
                .containsEntry("device_id", TERMINAL)
                .containsEntry("operator_id", adminId);
        assertThat((String) job.get("rendered_text")).contains("X REPORT - INTERIM");
    }

    @Test
    @DisplayName("with no shift open there is nothing to report on")
    void noShiftOpen() {
        jdbc.update("UPDATE shifts SET closed_at = now() WHERE id = ?", shiftId);

        assertErrorEnvelope(get("/api/v1/shifts/current/x-report", staff), 409, "CONFLICT");
    }

    @Test
    @DisplayName("the report needs a signed-in operator")
    void anonymousIsRejected() {
        assertThat(get("/api/v1/shifts/current/x-report", null).getStatusCode().value())
                .isEqualTo(401);
    }

    // ---- helpers -----------------------------------------------------------------------------

    private JsonNode xReport() {
        ResponseEntity<JsonNode> response = get("/api/v1/shifts/current/x-report", staff);
        assertThat(response.getStatusCode()).as("x-report failed: %s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    /** {@code null} asks for the totals line. */
    private static Map<String, Integer> row(JsonNode takings, String method) {
        JsonNode found = method == null
                ? takings.get("totals")
                : takingsRow(takings, method);
        return Map.of("gaming", found.get("gaming").asInt(),
                "fnb", found.get("fnb").asInt(),
                "tournament", found.get("tournament").asInt(),
                "booking", found.get("booking").asInt(),
                "total", found.get("total").asInt());
    }

    private static JsonNode takingsRow(JsonNode takings, String method) {
        for (JsonNode candidate : takings.get("byMethod")) {
            if (candidate.get("method").asText().equals(method)) {
                return candidate;
            }
        }
        throw new AssertionError("no row for " + method + " in " + takings);
    }

    private static List<String> methods(JsonNode report) {
        return report.get("takings").get("byMethod").findValuesAsText("method");
    }

    private Long openShift(int openingFloat) {
        ResponseEntity<JsonNode> opened =
                post("/api/v1/shifts", Map.of("openingFloat", openingFloat), staff);
        assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return opened.getBody().get("id").asLong();
    }

    private void expense(String description, String category, int amount) {
        ResponseEntity<JsonNode> recorded = post("/api/v1/expenses",
                Map.of("description", description, "category", category, "amount", amount), staff);
        assertThat(recorded.getStatusCode()).as("expense failed: %s", recorded.getBody())
                .isEqualTo(HttpStatus.CREATED);
    }

    private JsonNode settle(Map<String, Object> body) {
        ResponseEntity<JsonNode> response = post("/api/v1/payments", body, withKey());
        assertThat(response.getStatusCode()).as("settle failed: %s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private void voidPayment(long transactionId) {
        ResponseEntity<JsonNode> response = post("/api/v1/payments/" + transactionId + "/void",
                Map.of("reason", "Customer changed their mind"), staff);
        assertThat(response.getStatusCode()).as("void failed: %s", response.getBody())
                .isEqualTo(HttpStatus.OK);
    }

    private HttpHeaders withKey() {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(staff);
        headers.add("Idempotency-Key", UUID.randomUUID().toString());
        return headers;
    }

    private Long createStation(String name, String consoleType) {
        ResponseEntity<JsonNode> created = post("/api/v1/stations",
                Map.of("name", name, "consoleType", consoleType), staff);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody().get("id").asLong();
    }

    private Long createMember(String name, String phone, int points) {
        return jdbc.queryForObject(
                "INSERT INTO members (name, phone, points) VALUES (?, ?, ?) RETURNING id",
                Long.class, name, phone, points);
    }

    private Long createItem(String name, int price, int stock) {
        return jdbc.queryForObject(
                "INSERT INTO items (name, category, price, stock) VALUES (?, 'BEVERAGE', ?, ?) "
                        + "RETURNING id", Long.class, name, price, stock);
    }

    private void cartOn(Long sessionId, Long itemId, int qty) {
        Long cartId = jdbc.queryForObject("INSERT INTO carts (session_id) VALUES (?) RETURNING id",
                Long.class, sessionId);
        addLine(cartId, itemId, qty);
    }

    private Long counterCart(Long itemId, int qty) {
        Long cartId = jdbc.queryForObject("INSERT INTO carts (session_id) VALUES (NULL) RETURNING id",
                Long.class);
        addLine(cartId, itemId, qty);
        return cartId;
    }

    private void addLine(Long cartId, Long itemId, int qty) {
        jdbc.update("INSERT INTO cart_lines (cart_id, item_id, qty, unit_price) "
                        + "VALUES (?, ?, ?, (SELECT price FROM items WHERE id = ?))",
                cartId, itemId, qty, itemId);
    }
}
