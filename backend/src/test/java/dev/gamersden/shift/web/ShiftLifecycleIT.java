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
 * {@code POST /shifts} and {@code POST /shifts/current/close} against a real Postgres.
 *
 * <p>Two things are being proved. First, that a terminal has exactly one open shift — guarded by
 * the 409 and, underneath it, by {@code one_open_shift_per_terminal} — because every transaction
 * carries a {@code shift_id} and reconciliation stops meaning anything if "the open shift on T1"
 * is ambiguous (invariant §5.7).
 *
 * <p>Second, that a close is a single transaction with four consequences: the Z figures snapshotted
 * onto the row, the P2 job, an alert when the drawer disagrees, and the operator signed out. The
 * snapshot is the one place a derived value is deliberately written down (§5.4) — after the close,
 * the next shift's sales would make it unrecomputable.
 */
class ShiftLifecycleIT extends AbstractApiIntegrationTest {

    private static final int PS5_HALF_HOUR = 80;
    private static final int FLOAT = 2000;

    private FloorFixtures floor;
    private HttpHeaders staff;
    private Long stationId;

    @BeforeEach
    void seedFloor() {
        floor = new FloorFixtures(jdbc);
        staff = adminBearer();
        stationId = createStation("PS5-01", "PS5");
    }

    // ---- opening ------------------------------------------------------------------------------

    @Test
    @DisplayName("opening a shift returns the row the terminal will post against")
    void openingAShift() {
        ResponseEntity<JsonNode> opened = openShift(FLOAT);

        assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode shift = opened.getBody();
        assertThat(shift.get("terminal").asText()).isEqualTo(TERMINAL);
        assertThat(shift.get("staffId").asLong()).isEqualTo(adminId);
        assertThat(shift.get("openingFloat").asInt()).isEqualTo(FLOAT);
        assertThat(shift.get("open").asBoolean()).isTrue();
        assertThat(shift.get("openedAt").asText()).isNotBlank();
        // The Z snapshot does not exist yet, so those fields are absent rather than null.
        assertThat(shift.has("closedAt")).isFalse();
        assertThat(shift.has("expectedCash")).isFalse();
    }

    @Test
    @DisplayName("a second shift on the same terminal is 409 SHIFT_ALREADY_OPEN")
    void secondOpenShiftIsRefused() {
        long first = openShift(FLOAT).getBody().get("id").asLong();

        ResponseEntity<JsonNode> second = openShift(500);

        assertErrorEnvelope(second, 409, "SHIFT_ALREADY_OPEN");
        assertThat(second.getBody().get("error").get("details").get("shiftId").asLong())
                .isEqualTo(first);
        assertThat(countOf("shifts")).isEqualTo(1);
    }

    @Test
    @DisplayName("another terminal opens its own shift regardless")
    void aSecondTerminalIsItsOwnTill() {
        openShift(FLOAT);

        HttpHeaders otherTill = bearer(login(adminId, ADMIN_PIN, "T2").getBody()
                .get("accessToken").asText());
        ResponseEntity<JsonNode> opened =
                post("/api/v1/shifts", Map.of("openingFloat", 1000), otherTill);

        assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(opened.getBody().get("terminal").asText()).isEqualTo("T2");
        assertThat(countOf("shifts")).isEqualTo(2);
    }

    // ---- closing ------------------------------------------------------------------------------

    @Test
    @DisplayName("a square close writes the Z snapshot, queues P2, and raises no alert")
    void closingASquareDrawer() {
        long shiftId = openShift(FLOAT).getBody().get("id").asLong();
        Long sessionId = floor.runningSessionOn(stationId, shiftId, 2, PS5_HALF_HOUR, 0);
        settle(Map.of("target", Map.of("sessionId", sessionId),
                "splits", List.of(Map.of("method", "CASH", "amount", 160))));
        expense("Cleaning supplies", "SUPPLIES", 300);

        // 2000 + 160 - 300 = 1860 in the drawer.
        JsonNode z = close(1860, "Handed over to the night shift");

        assertThat(z.get("kind").asText()).isEqualTo("Z");
        assertThat(z.get("shiftId").asLong()).isEqualTo(shiftId);
        assertThat(z.get("closedAt").asText()).isNotBlank();
        assertThat(z.get("handoverNote").asText()).isEqualTo("Handed over to the night shift");
        JsonNode cash = z.get("cash");
        assertThat(cash.get("expected").asInt()).isEqualTo(1860);
        assertThat(cash.get("counted").asInt()).isEqualTo(1860);
        assertThat(cash.get("discrepancy").asInt()).isZero();

        // The snapshot on the row is what the Z printed, and the shift is closed.
        Map<String, Object> row = jdbc.queryForMap("SELECT closed_at, counted_cash, expected_cash, "
                + "discrepancy, handover_note FROM shifts WHERE id = ?", shiftId);
        assertThat(row.get("closed_at")).isNotNull();
        assertThat(row).containsEntry("counted_cash", 1860)
                .containsEntry("expected_cash", 1860)
                .containsEntry("discrepancy", 0)
                .containsEntry("handover_note", "Handed over to the night shift");

        // P2, in the same transaction, against this shift.
        Map<String, Object> job = jdbc.queryForMap("SELECT type, ref_id, status, device_id, "
                + "operator_id, rendered_text FROM print_jobs WHERE id = ?",
                z.get("printJobId").asLong());
        assertThat(job).containsEntry("type", "Z_REPORT")
                .containsEntry("ref_id", shiftId)
                .containsEntry("status", "QUEUED")
                .containsEntry("device_id", TERMINAL)
                .containsEntry("operator_id", adminId);
        assertThat((String) job.get("rendered_text")).contains("Z REPORT").contains("SIGNATURE");

        assertThat(countOf("alerts")).isZero();
    }

    @Test
    @DisplayName("a drawer that does not match writes a discrepancy alert")
    void aShortDrawerRaisesAnAlert() {
        long shiftId = openShift(FLOAT).getBody().get("id").asLong();
        Long sessionId = floor.runningSessionOn(stationId, shiftId, 2, PS5_HALF_HOUR, 0);
        settle(Map.of("target", Map.of("sessionId", sessionId),
                "splits", List.of(Map.of("method", "CASH", "amount", 160))));

        // 2160 expected, 2100 counted: 60 short.
        JsonNode z = close(2100, null);

        assertThat(z.get("cash").get("expected").asInt()).isEqualTo(2160);
        assertThat(z.get("cash").get("discrepancy").asInt()).isEqualTo(-60);
        assertThat(jdbc.queryForObject("SELECT discrepancy FROM shifts WHERE id = ?",
                Integer.class, shiftId)).isEqualTo(-60);

        Map<String, Object> alert = jdbc.queryForMap(
                "SELECT type, title, body, read FROM alerts ORDER BY id DESC LIMIT 1");
        assertThat(alert).containsEntry("type", "CASH_DISCREPANCY").containsEntry("read", false);
        assertThat((String) alert.get("title")).contains("short").contains(TERMINAL);
        assertThat((String) alert.get("body")).contains("Shift #" + shiftId).contains("-60");
    }

    @Test
    @DisplayName("an over drawer is an alert too, the other way round")
    void anOverDrawerRaisesAnAlert() {
        openShift(FLOAT);

        JsonNode z = close(FLOAT + 40, null);

        assertThat(z.get("cash").get("discrepancy").asInt()).isEqualTo(40);
        assertThat((String) jdbc.queryForObject("SELECT title FROM alerts ORDER BY id DESC LIMIT 1",
                String.class)).contains("over");
    }

    @Test
    @DisplayName("closing signs the shift's operator out of that terminal")
    void closingSignsTheOperatorOut() {
        ResponseEntity<JsonNode> signIn = login(adminId, ADMIN_PIN);
        String refresh = refreshCookieOf(signIn).orElseThrow();
        HttpHeaders session = bearer(signIn.getBody().get("accessToken").asText());
        post("/api/v1/shifts", Map.of("openingFloat", FLOAT), session);

        post("/api/v1/shifts/current/close", Map.of("countedCash", FLOAT), session);

        // The refresh cookie the till was holding no longer renews — it is back at the PIN screen.
        assertThat(post("/api/v1/auth/refresh", null, cookie(refresh)).getStatusCode().value())
                .isEqualTo(401);
    }

    @Test
    @DisplayName("closing twice is refused — there is no longer a shift open here")
    void closingTwice() {
        openShift(FLOAT);
        close(FLOAT, null);

        assertErrorEnvelope(post("/api/v1/shifts/current/close",
                Map.of("countedCash", FLOAT), staff), 409, "CONFLICT");
    }

    @Test
    @DisplayName("a cashier may not close someone else's shift")
    void aCashierClosesOnlyTheirOwnShift() {
        openShift(FLOAT);
        Long cashierId = createStaff("Nusrat", "CASHIER", "4321");
        HttpHeaders cashier = bearerFor(cashierId, "4321");

        ResponseEntity<JsonNode> refused = post("/api/v1/shifts/current/close",
                Map.of("countedCash", FLOAT), cashier);

        assertErrorEnvelope(refused, 403, "FORBIDDEN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shifts WHERE closed_at IS NULL",
                Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("a negative drawer count is refused before anything is written")
    void aNegativeCountIsRefused() {
        long shiftId = openShift(FLOAT).getBody().get("id").asLong();

        ResponseEntity<JsonNode> refused = post("/api/v1/shifts/current/close",
                Map.of("countedCash", -1), staff);

        assertThat(refused.getStatusCode().value()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT closed_at FROM shifts WHERE id = ?",
                java.sql.Timestamp.class, shiftId)).isNull();
    }

    // ---- history ------------------------------------------------------------------------------

    @Test
    @DisplayName("the history lists shifts newest first, with the open one included")
    void shiftHistory() {
        openShift(FLOAT);
        close(FLOAT, null);
        long open = openShift(500).getBody().get("id").asLong();

        JsonNode page = get("/api/v1/shifts", staff).getBody();

        assertThat(page.get("totalElements").asInt()).isEqualTo(2);
        assertThat(page.get("content").get(0).get("id").asLong()).isEqualTo(open);
        assertThat(page.get("content").get(0).get("open").asBoolean()).isTrue();
        assertThat(page.get("content").get(1).get("open").asBoolean()).isFalse();
    }

    // ---- helpers -----------------------------------------------------------------------------

    private ResponseEntity<JsonNode> openShift(int openingFloat) {
        return post("/api/v1/shifts", Map.of("openingFloat", openingFloat), staff);
    }

    private JsonNode close(int countedCash, String handoverNote) {
        Map<String, Object> body = handoverNote == null
                ? Map.of("countedCash", countedCash)
                : Map.of("countedCash", countedCash, "handoverNote", handoverNote);
        ResponseEntity<JsonNode> response = post("/api/v1/shifts/current/close", body, staff);
        assertThat(response.getStatusCode()).as("close failed: %s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private void expense(String description, String category, int amount) {
        assertThat(post("/api/v1/expenses",
                Map.of("description", description, "category", category, "amount", amount), staff)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private JsonNode settle(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(staff);
        headers.add("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<JsonNode> response = post("/api/v1/payments", body, headers);
        assertThat(response.getStatusCode()).as("settle failed: %s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private Long createStaff(String name, String role, String pin) {
        ResponseEntity<JsonNode> created = post("/api/v1/staff",
                Map.of("name", name, "role", role, "pin", pin), staff);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody().get("id").asLong();
    }

    private Long createStation(String name, String consoleType) {
        ResponseEntity<JsonNode> created = post("/api/v1/stations",
                Map.of("name", name, "consoleType", consoleType), staff);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody().get("id").asLong();
    }

    private int countOf(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
