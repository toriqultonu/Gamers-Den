package dev.gamersden.billing.web;

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
 * {@code POST /payments/{id}/void} against a real Postgres.
 *
 * <p>A void is not an undo button on a row — it is a second, negative transaction (invariant
 * §5.7). The sale stays in the books exactly as it was printed, flagged with its reason, and every
 * side effect it had is walked back through the same doors that applied it. These tests assert
 * both halves: that the reversal exists and is the exact mirror of the sale, and that the world it
 * touched — blocks, shelf, cart, ledgers, balances — is back where it started.
 */
class PaymentVoidIT extends AbstractApiIntegrationTest {

    private static final int PS5_HALF_HOUR = 80;
    private static final int PEPSI = 60;

    private FloorFixtures floor;
    private HttpHeaders staff;
    private Long stationId;
    private Long shiftId;

    @BeforeEach
    void seedFloor() {
        floor = new FloorFixtures(jdbc);
        staff = adminBearer();
        stationId = createStation("PS5-01", "PS5");
        shiftId = floor.openShift(adminId, TERMINAL);
    }

    @Test
    @DisplayName("a void reverses the money, the blocks, the shelf, the cart and both ledgers")
    void voidReversesEverything() {
        Long memberId = createMember("Rifat Hasan", "+8801712448190", 500, 400);
        Long sessionId = floor.runningSessionOn(stationId, shiftId, 2, PS5_HALF_HOUR, 0);
        attach(memberId, sessionId);
        Long itemId = createItem("Pepsi 250ml", PEPSI, 100);
        Long cartId = cartOn(sessionId, itemId, 2);

        // 280 gross, 100 points off, 80 from the wallet and 100 in cash.
        JsonNode sale = settle(Map.of("target", Map.of("sessionId", sessionId),
                "redeemPoints", 100,
                "splits", List.of(Map.of("method", "WALLET", "amount", 80),
                        Map.of("method", "CASH", "amount", 100))));
        long txId = sale.get("transactionId").asLong();
        assertThat(pointsOf(memberId)).isEqualTo(409);
        assertThat(walletOf(memberId)).isEqualTo(320);

        JsonNode voided = voidPayment(txId, "Customer changed their mind");
        long reversalId = voided.get("transactionId").asLong();

        assertThat(voided.get("voidedTransactionId").asLong()).isEqualTo(txId);
        assertThat(voided.get("refunded").asInt()).isEqualTo(-180);
        assertThat(voided.get("publicId").asText()).isNotEqualTo(sale.get("publicId").asText());

        // The sale row itself is untouched apart from its flag — the snapshot is immutable (§5.4).
        Map<String, Object> original = transaction(txId);
        assertThat(original).containsEntry("voided", true)
                .containsEntry("void_reason", "Customer changed their mind")
                .containsEntry("gaming_amount", 160)
                .containsEntry("total_due", 180);

        // The reversal is its exact mirror, posted to the same shift.
        Map<String, Object> reversal = transaction(reversalId);
        assertThat(reversal).containsEntry("gaming_amount", -160)
                .containsEntry("fnb_amount", -120)
                .containsEntry("points_redeemed", -100)
                .containsEntry("points_earned", -9)
                .containsEntry("total_due", -180)
                .containsEntry("shift_id", shiftId)
                .containsEntry("voided", false);

        List<Map<String, Object>> tenders = jdbc.queryForList(
                "SELECT method, amount FROM payment_splits WHERE tx_id = ? ORDER BY id", reversalId);
        assertThat(tenders).hasSize(2);
        assertThat(tenders.get(0)).containsEntry("method", "WALLET").containsEntry("amount", -80);
        assertThat(tenders.get(1)).containsEntry("method", "CASH").containsEntry("amount", -100);

        // The time is billable again, and the seat never stopped running.
        assertThat(paidTxIdsOn(sessionId)).containsExactly(null, null);
        assertThat(floor.stateOf(sessionId)).isEqualTo("RUNNING");
        assertThat(get("/api/v1/sessions/" + sessionId + "/bill", staff).getBody()
                .get("netTotal").asInt()).isEqualTo(280);

        // The shelf is back, with a VOID movement explaining why.
        assertThat(stockOf(itemId)).isEqualTo(100);
        List<Map<String, Object>> returned = jdbc.queryForList("SELECT delta, ref_tx_id FROM "
                + "stock_movements WHERE item_id = ? AND reason = 'VOID'", itemId);
        assertThat(returned).hasSize(1);
        assertThat(returned.get(0)).containsEntry("delta", 2).containsEntry("ref_tx_id", reversalId);
        assertThat(jdbc.queryForObject("SELECT settled FROM carts WHERE id = ?", Boolean.class, cartId))
                .isFalse();

        // Loyalty is exactly where it was before the sale, by reversal rows rather than deletions.
        assertThat(pointsOf(memberId)).isEqualTo(500);
        assertThat(walletOf(memberId)).isEqualTo(400);
        assertThat(jdbc.queryForList("SELECT kind FROM points_ledger WHERE ref_tx_id = ? ORDER BY id",
                String.class, reversalId)).containsExactly("REVERSAL", "REVERSAL");
        assertThat(jdbc.queryForList("SELECT kind FROM wallet_ledger WHERE ref_tx_id = ?",
                String.class, reversalId)).containsExactly("REVERSAL");
    }

    @Test
    @DisplayName("a voided sale can be settled again, exactly as it was before")
    void theBillCanBeTakenAgain() {
        Long sessionId = floor.runningSessionOn(stationId, shiftId, 2, PS5_HALF_HOUR, 0);
        long txId = settle(Map.of("target", Map.of("sessionId", sessionId),
                "splits", List.of(Map.of("method", "CASH", "amount", 160))))
                .get("transactionId").asLong();
        voidPayment(txId, "Wrong station");

        JsonNode again = settle(Map.of("target", Map.of("sessionId", sessionId),
                "splits", List.of(Map.of("method", "CASH", "amount", 160))));

        assertThat(again.get("transactionId").asLong()).isNotEqualTo(txId);
        assertThat(paidTxIdsOn(sessionId))
                .containsExactly(again.get("transactionId").asLong(),
                        again.get("transactionId").asLong());
    }

    @Test
    @DisplayName("voiding is Manager+ — a cashier is refused")
    void cashierCannotVoid() {
        Long sessionId = floor.runningSessionOn(stationId, shiftId, 1, PS5_HALF_HOUR, 0);
        long txId = settle(Map.of("target", Map.of("sessionId", sessionId),
                "splits", List.of(Map.of("method", "CASH", "amount", 80))))
                .get("transactionId").asLong();

        ResponseEntity<JsonNode> response = post("/api/v1/payments/" + txId + "/void",
                Map.of("reason", "Mis-keyed"), cashierBearer());

        assertErrorEnvelope(response, 403, "FORBIDDEN");
        assertThat(transaction(txId)).containsEntry("voided", false);
        assertThat(paidTxIdsOn(sessionId)).containsExactly(txId);
    }

    @Test
    @DisplayName("a void needs a reason")
    void reasonIsRequired() {
        Long sessionId = floor.runningSessionOn(stationId, shiftId, 1, PS5_HALF_HOUR, 0);
        long txId = settle(Map.of("target", Map.of("sessionId", sessionId),
                "splits", List.of(Map.of("method", "CASH", "amount", 80))))
                .get("transactionId").asLong();

        assertErrorEnvelope(post("/api/v1/payments/" + txId + "/void", Map.of("reason", "  "), staff),
                400, "VALIDATION_FAILED");
        assertThat(transaction(txId)).containsEntry("voided", false);
    }

    @Test
    @DisplayName("voiding the same sale twice is 409 — one reversal, not two")
    void voidIsOnlyPossibleOnce() {
        Long sessionId = floor.runningSessionOn(stationId, shiftId, 1, PS5_HALF_HOUR, 0);
        long txId = settle(Map.of("target", Map.of("sessionId", sessionId),
                "splits", List.of(Map.of("method", "CASH", "amount", 80))))
                .get("transactionId").asLong();
        voidPayment(txId, "Mis-keyed");

        assertErrorEnvelope(post("/api/v1/payments/" + txId + "/void",
                Map.of("reason", "Mis-keyed again"), staff), 409, "CONFLICT");
        assertThat(countOf("transactions")).isEqualTo(2);
    }

    @Test
    @DisplayName("a reversal is not itself voidable")
    void aRefundCannotBeVoided() {
        Long sessionId = floor.runningSessionOn(stationId, shiftId, 1, PS5_HALF_HOUR, 0);
        long txId = settle(Map.of("target", Map.of("sessionId", sessionId),
                "splits", List.of(Map.of("method", "CASH", "amount", 80))))
                .get("transactionId").asLong();
        long reversalId = voidPayment(txId, "Mis-keyed").get("transactionId").asLong();

        assertErrorEnvelope(post("/api/v1/payments/" + reversalId + "/void",
                Map.of("reason", "Undo the undo"), staff), 409, "CONFLICT");
    }

    @Test
    @DisplayName("a void has to land in the shift that took the money")
    void sameShiftOnly() {
        Long sessionId = floor.runningSessionOn(stationId, shiftId, 1, PS5_HALF_HOUR, 0);
        long txId = settle(Map.of("target", Map.of("sessionId", sessionId),
                "splits", List.of(Map.of("method", "CASH", "amount", 80))))
                .get("transactionId").asLong();

        jdbc.update("UPDATE shifts SET closed_at = now() WHERE id = ?", shiftId);
        floor.openShift(adminId, TERMINAL);

        assertErrorEnvelope(post("/api/v1/payments/" + txId + "/void",
                Map.of("reason", "Yesterday's mistake"), staff), 409, "CONFLICT");
        assertThat(transaction(txId)).containsEntry("voided", false);
        assertThat(paidTxIdsOn(sessionId)).containsExactly(txId);
    }

    @Test
    @DisplayName("a void that would leave the member owing points is refused, and writes nothing")
    void pointsAlreadySpent() {
        Long memberId = createMember("Nafis Iqbal", "+8801533770210", 0, 0);
        Long sessionId = floor.runningSessionOn(stationId, shiftId, 2, PS5_HALF_HOUR, 0);
        attach(memberId, sessionId);
        long txId = settle(Map.of("target", Map.of("sessionId", sessionId),
                "splits", List.of(Map.of("method", "CASH", "amount", 160))))
                .get("transactionId").asLong();
        // The 8 points the sale earned have since been converted to wallet credit.
        assertThat(pointsOf(memberId)).isEqualTo(8);
        jdbc.update("UPDATE members SET points = 0 WHERE id = ?", memberId);

        assertErrorEnvelope(post("/api/v1/payments/" + txId + "/void",
                Map.of("reason", "Mis-keyed"), staff), 409, "INSUFFICIENT_POINTS");

        assertThat(transaction(txId)).containsEntry("voided", false);
        assertThat(countOf("transactions")).isEqualTo(1);
        assertThat(paidTxIdsOn(sessionId)).containsExactly(txId, txId);
    }

    @Test
    @DisplayName("an unknown transaction is 404 in the error envelope")
    void unknownTransaction() {
        assertErrorEnvelope(post("/api/v1/payments/999999/void", Map.of("reason", "Nope"), staff),
                404, "NOT_FOUND");
    }

    // ---- helpers -----------------------------------------------------------------------------

    private JsonNode settle(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(staff);
        headers.add("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<JsonNode> response = post("/api/v1/payments", body, headers);
        assertThat(response.getStatusCode()).as("settle failed: %s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private JsonNode voidPayment(long txId, String reason) {
        ResponseEntity<JsonNode> response = post("/api/v1/payments/" + txId + "/void",
                Map.of("reason", reason), staff);
        assertThat(response.getStatusCode()).as("void failed: %s", response.getBody())
                .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private HttpHeaders cashierBearer() {
        ResponseEntity<JsonNode> created = post("/api/v1/staff",
                Map.of("name", "Tanvir", "role", "CASHIER", "pin", "4455"), staff);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return bearerFor(created.getBody().get("id").asLong(), "4455");
    }

    private Map<String, Object> transaction(long txId) {
        return jdbc.queryForMap("SELECT gaming_amount, fnb_amount, points_redeemed, points_earned, "
                + "total_due, shift_id, voided, void_reason FROM transactions WHERE id = ?", txId);
    }

    private List<Long> paidTxIdsOn(Long sessionId) {
        return jdbc.queryForList("SELECT paid_tx_id FROM session_blocks WHERE session_id = ? "
                + "AND NOT removed ORDER BY id", Long.class, sessionId);
    }

    private int countOf(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private int stockOf(Long itemId) {
        return jdbc.queryForObject("SELECT stock FROM items WHERE id = ?", Integer.class, itemId);
    }

    private int pointsOf(Long memberId) {
        return jdbc.queryForObject("SELECT points FROM members WHERE id = ?", Integer.class, memberId);
    }

    private int walletOf(Long memberId) {
        return jdbc.queryForObject("SELECT wallet FROM members WHERE id = ?", Integer.class, memberId);
    }

    private void attach(Long memberId, Long sessionId) {
        jdbc.update("UPDATE sessions SET member_id = ? WHERE id = ?", memberId, sessionId);
    }

    private Long createStation(String name, String consoleType) {
        ResponseEntity<JsonNode> created = post("/api/v1/stations",
                Map.of("name", name, "consoleType", consoleType), staff);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody().get("id").asLong();
    }

    private Long createMember(String name, String phone, int points, int wallet) {
        return jdbc.queryForObject(
                "INSERT INTO members (name, phone, points, wallet) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, name, phone, points, wallet);
    }

    private Long createItem(String name, int price, int stock) {
        return jdbc.queryForObject(
                "INSERT INTO items (name, category, price, stock) VALUES (?, 'BEVERAGE', ?, ?) "
                        + "RETURNING id", Long.class, name, price, stock);
    }

    private Long cartOn(Long sessionId, Long itemId, int qty) {
        Long cartId = jdbc.queryForObject("INSERT INTO carts (session_id) VALUES (?) RETURNING id",
                Long.class, sessionId);
        jdbc.update("INSERT INTO cart_lines (cart_id, item_id, qty, unit_price) "
                        + "VALUES (?, ?, ?, (SELECT price FROM items WHERE id = ?))",
                cartId, itemId, qty, itemId);
        return cartId;
    }
}
