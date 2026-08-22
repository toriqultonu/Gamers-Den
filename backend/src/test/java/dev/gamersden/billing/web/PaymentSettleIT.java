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
 * {@code POST /payments} against a real Postgres — the release-critical half of B10.
 *
 * <p>What is proved here is not arithmetic ({@code SettlementTest} owns that) but
 * <strong>atomicity</strong>: one settle moves six things at once — the transaction snapshot, its
 * tenders, the blocks it paid for, the shelf, both loyalty ledgers and the receipt print job — and
 * either all of them move or none do (invariant §5.3). Each test therefore asserts the whole
 * footprint, not just the response.
 */
class PaymentSettleIT extends AbstractApiIntegrationTest {

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

    // ---- the whole footprint of one settle ---------------------------------------------------

    @Test
    @DisplayName("one settle writes the payment, the blocks, the stock, both ledgers and the print job")
    void oneSettleWritesEverythingAtOnce() {
        Long memberId = createMember("Rifat Hasan", "+8801712448190", 500, 400);
        Long sessionId = floor.runningSessionOn(stationId, shiftId, 2, PS5_HALF_HOUR, 0);
        attach(memberId, sessionId);
        Long itemId = createItem("Pepsi 250ml", PEPSI, 100);
        Long cartId = cartOn(sessionId, itemId, 2);

        // Gaming 2x80 + F&B 2x60 = 280, less 100 points = 180 to tender.
        JsonNode settled = settle(Map.of(
                "target", Map.of("sessionId", sessionId),
                "redeemPoints", 100,
                "splits", List.of(
                        Map.of("method", "CASH", "amount", 100),
                        Map.of("method", "BKASH", "amount", 80, "paymentRef", "8XK21QW7"))));

        long txId = settled.get("transactionId").asLong();
        long printJobId = settled.get("printJobId").asLong();
        assertThat(settled.get("publicId").asText()).isEqualTo("GD-" + dayMonth() + "-001");
        // Not sold here, so not on the wire at all (default-property-inclusion: non_null).
        assertThat(settled.has("entryTokens")).isFalse();
        assertThat(settled.has("queueTokens")).isFalse();

        Map<String, Object> tx = transaction(txId);
        assertThat(tx).containsEntry("gaming_amount", 160)
                .containsEntry("fnb_amount", 120)
                .containsEntry("tournament_amount", 0)
                .containsEntry("booking_amount", 0)
                .containsEntry("points_redeemed", 100)
                // floor(180 / 20) — earned on what was paid, not on the gross.
                .containsEntry("points_earned", 9)
                .containsEntry("total_due", 180)
                .containsEntry("shift_id", shiftId)
                .containsEntry("session_id", sessionId)
                .containsEntry("cart_id", cartId)
                .containsEntry("member_id", memberId)
                .containsEntry("voided", false);

        List<Map<String, Object>> tenders = jdbc.queryForList(
                "SELECT method, amount, payment_ref, verify_state FROM payment_splits "
                        + "WHERE tx_id = ? ORDER BY id", txId);
        assertThat(tenders).hasSize(2);
        assertThat(tenders.get(0)).containsEntry("method", "CASH").containsEntry("amount", 100)
                .containsEntry("verify_state", "MANUAL");
        assertThat(tenders.get(0).get("payment_ref")).isNull();
        assertThat(tenders.get(1)).containsEntry("method", "BKASH").containsEntry("amount", 80)
                .containsEntry("payment_ref", "8XK21QW7")
                // MVP truth for bKash/Nagad is the manually entered TrxID (api-contract.md).
                .containsEntry("verify_state", "MANUAL");

        // Blocks: paid, but the seat is untouched — paying is not ending (invariant §5.9).
        assertThat(paidTxIdsOn(sessionId)).containsExactly(txId, txId);
        assertThat(floor.stateOf(sessionId)).isEqualTo("RUNNING");

        // Stock moved exactly once, with an audit row pointing at the transaction that moved it.
        assertThat(stockOf(itemId)).isEqualTo(98);
        List<Map<String, Object>> sold = jdbc.queryForList("SELECT delta, ref_tx_id FROM "
                + "stock_movements WHERE item_id = ? AND reason = 'SALE'", itemId);
        assertThat(sold).hasSize(1);
        assertThat(sold.get(0)).containsEntry("delta", -2).containsEntry("ref_tx_id", txId);
        assertThat(jdbc.queryForObject("SELECT settled FROM carts WHERE id = ?", Boolean.class, cartId))
                .isTrue();

        // Both ledgers, both columns, one transaction.
        List<Map<String, Object>> loyalty = jdbc.queryForList("SELECT delta, kind, ref_tx_id FROM "
                + "points_ledger WHERE member_id = ? ORDER BY id", memberId);
        assertThat(loyalty).hasSize(2);
        assertThat(loyalty.get(0)).containsEntry("delta", -100).containsEntry("kind", "REDEEM_BILL")
                .containsEntry("ref_tx_id", txId);
        assertThat(loyalty.get(1)).containsEntry("delta", 9).containsEntry("kind", "EARN")
                .containsEntry("ref_tx_id", txId);
        assertThat(pointsOf(memberId)).isEqualTo(409);
        assertThat(walletOf(memberId)).isEqualTo(400);

        // The receipt was rendered and queued inside the same transaction (invariant §5.3, §5.5).
        Map<String, Object> job = jdbc.queryForMap(
                "SELECT type, ref_id, status, attempts, device_id, operator_id, is_reprint, "
                        + "rendered, rendered_text FROM print_jobs WHERE id = ?", printJobId);
        assertThat(job).containsEntry("type", "RECEIPT")
                .containsEntry("ref_id", txId)
                .containsEntry("status", "QUEUED")
                .containsEntry("attempts", 0)
                .containsEntry("device_id", TERMINAL)
                .containsEntry("operator_id", adminId)
                .containsEntry("is_reprint", false);
        assertThat((byte[]) job.get("rendered")).isNotEmpty();
        assertThat((String) job.get("rendered_text"))
                .contains("GD-" + dayMonth() + "-001")
                .contains("PS5-01")
                .contains("TOTAL");
    }

    @Test
    @DisplayName("the seat keeps running and only the time bought since is billed again")
    void theSessionContinuesAfterASettle() {
        Long sessionId = floor.runningSessionOn(stationId, shiftId, 2, PS5_HALF_HOUR, 0);
        settle(Map.of("target", Map.of("sessionId", sessionId),
                "splits", List.of(Map.of("method", "CASH", "amount", 160))));

        jdbc.update("INSERT INTO session_blocks (session_id, price) VALUES (?, ?)",
                sessionId, PS5_HALF_HOUR);

        JsonNode bill = get("/api/v1/sessions/" + sessionId + "/bill", staff).getBody();
        assertThat(bill.get("gamingDue").asInt()).isEqualTo(PS5_HALF_HOUR);
        assertThat(bill.get("prepaidCredit").asInt()).isEqualTo(160);
        assertThat(floor.stateOf(sessionId)).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("a counter cart settles on its own, with no session and no loyalty")
    void counterSale() {
        Long itemId = createItem("Doritos", 90, 20);
        Long cartId = counterCart(itemId, 3);

        JsonNode settled = settle(Map.of("target", Map.of("cartId", cartId),
                "splits", List.of(Map.of("method", "CASH", "amount", 270))));

        Map<String, Object> tx = transaction(settled.get("transactionId").asLong());
        assertThat(tx).containsEntry("fnb_amount", 270)
                .containsEntry("gaming_amount", 0)
                .containsEntry("total_due", 270)
                .containsEntry("cart_id", cartId);
        assertThat(tx.get("session_id")).isNull();
        assertThat(tx.get("member_id")).isNull();
        assertThat(stockOf(itemId)).isEqualTo(17);
    }

    @Test
    @DisplayName("each sale takes the next public id of the venue day")
    void publicIdsRunInSequence() {
        Long first = floor.runningSessionOn(stationId, shiftId, 1, PS5_HALF_HOUR, 0);
        Long secondStation = createStation("PS5-02", "PS5");
        Long second = floor.runningSessionOn(secondStation, shiftId, 1, PS5_HALF_HOUR, 0);

        String one = settle(Map.of("target", Map.of("sessionId", first),
                "splits", List.of(Map.of("method", "CASH", "amount", 80)))).get("publicId").asText();
        String two = settle(Map.of("target", Map.of("sessionId", second),
                "splits", List.of(Map.of("method", "CASH", "amount", 80)))).get("publicId").asText();

        assertThat(one).isEqualTo("GD-" + dayMonth() + "-001");
        assertThat(two).isEqualTo("GD-" + dayMonth() + "-002");
    }

    // ---- idempotency -------------------------------------------------------------------------

    @Test
    @DisplayName("a replayed settle returns the same transactionId and printJobId, and charges once")
    void replayIsTheSameSaleOrNoSaleAtAll() {
        Long memberId = createMember("Nafis Iqbal", "+8801533770210", 0, 0);
        Long sessionId = floor.runningSessionOn(stationId, shiftId, 2, PS5_HALF_HOUR, 0);
        attach(memberId, sessionId);
        Long itemId = createItem("Pepsi 250ml", PEPSI, 100);
        cartOn(sessionId, itemId, 2);

        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("target", Map.of("sessionId", sessionId),
                "splits", List.of(Map.of("method", "CASH", "amount", 280)));

        ResponseEntity<JsonNode> first = post("/api/v1/payments", body, withKey(key));
        ResponseEntity<JsonNode> replay = post("/api/v1/payments", body, withKey(key));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getHeaders().getFirst("Idempotency-Replayed")).isNull();
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(replay.getBody().get("transactionId").asLong())
                .isEqualTo(first.getBody().get("transactionId").asLong());
        assertThat(replay.getBody().get("printJobId").asLong())
                .isEqualTo(first.getBody().get("printJobId").asLong());

        // And nothing ran twice: one transaction, one job, one stock movement, 14 points once.
        assertThat(countOf("transactions")).isEqualTo(1);
        assertThat(countOf("print_jobs")).isEqualTo(1);
        assertThat(countOf("payment_splits")).isEqualTo(1);
        assertThat(stockOf(itemId)).isEqualTo(98);
        assertThat(pointsOf(memberId)).isEqualTo(14);
    }

    @Test
    @DisplayName("the same key with a different body is 409 IDEMPOTENCY_REPLAY, not a second sale")
    void sameKeyDifferentBody() {
        Long sessionId = floor.runningSessionOn(stationId, shiftId, 2, PS5_HALF_HOUR, 0);
        String key = UUID.randomUUID().toString();

        post("/api/v1/payments", Map.of("target", Map.of("sessionId", sessionId),
                "splits", List.of(Map.of("method", "CASH", "amount", 160))), withKey(key));
        ResponseEntity<JsonNode> second = post("/api/v1/payments",
                Map.of("target", Map.of("sessionId", sessionId),
                        "splits", List.of(Map.of("method", "CASH", "amount", 80))), withKey(key));

        assertErrorEnvelope(second, 409, "IDEMPOTENCY_REPLAY");
        assertThat(countOf("transactions")).isEqualTo(1);
    }

    @Test
    @DisplayName("a settle without an Idempotency-Key is refused before it reaches the money")
    void keyIsRequired() {
        Long sessionId = floor.runningSessionOn(stationId, shiftId, 1, PS5_HALF_HOUR, 0);

        ResponseEntity<JsonNode> response = post("/api/v1/payments",
                Map.of("target", Map.of("sessionId", sessionId),
                        "splits", List.of(Map.of("method", "CASH", "amount", 80))), staff);

        assertErrorEnvelope(response, 400, "VALIDATION_FAILED");
        assertThat(countOf("transactions")).isZero();
    }

    // ---- refusals leave nothing behind --------------------------------------------------------

    @Test
    @DisplayName("409 SPLIT_MISMATCH leaves zero writes — no transaction, no stock, no ledger, no paper")
    void splitMismatchWritesNothing() {
        Long memberId = createMember("Rifat Hasan", "+8801712448190", 500, 400);
        Long sessionId = floor.runningSessionOn(stationId, shiftId, 2, PS5_HALF_HOUR, 0);
        attach(memberId, sessionId);
        Long itemId = createItem("Pepsi 250ml", PEPSI, 100);
        Long cartId = cartOn(sessionId, itemId, 2);

        ResponseEntity<JsonNode> response = post("/api/v1/payments",
                Map.of("target", Map.of("sessionId", sessionId),
                        "redeemPoints", 100,
                        // 280 gross less 100 points is 180; 150 is short.
                        "splits", List.of(Map.of("method", "CASH", "amount", 150))),
                withKey(UUID.randomUUID().toString()));

        assertErrorEnvelope(response, 409, "SPLIT_MISMATCH");
        JsonNode details = response.getBody().get("error").get("details");
        assertThat(details.get("expected").asInt()).isEqualTo(180);
        assertThat(details.get("provided").asInt()).isEqualTo(150);

        assertThat(countOf("transactions")).isZero();
        assertThat(countOf("payment_splits")).isZero();
        assertThat(countOf("print_jobs")).isZero();
        assertThat(countOf("points_ledger")).isZero();
        assertThat(countOf("wallet_ledger")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movements WHERE reason = 'SALE'",
                Integer.class)).isZero();
        assertThat(paidTxIdsOn(sessionId)).containsExactly(null, null);
        assertThat(stockOf(itemId)).isEqualTo(100);
        assertThat(pointsOf(memberId)).isEqualTo(500);
        assertThat(jdbc.queryForObject("SELECT settled FROM carts WHERE id = ?", Boolean.class, cartId))
                .isFalse();
    }

    @Test
    @DisplayName("a bKash tender with no TrxID is 409 PAYMENT_REF_REQUIRED and writes nothing")
    void bkashNeedsItsReference() {
        Long sessionId = floor.runningSessionOn(stationId, shiftId, 1, PS5_HALF_HOUR, 0);

        ResponseEntity<JsonNode> response = post("/api/v1/payments",
                Map.of("target", Map.of("sessionId", sessionId),
                        "splits", List.of(Map.of("method", "BKASH", "amount", 80))),
                withKey(UUID.randomUUID().toString()));

        assertErrorEnvelope(response, 409, "PAYMENT_REF_REQUIRED");
        assertThat(countOf("transactions")).isZero();
    }

    @Test
    @DisplayName("spending past the wallet is 409 WALLET_INSUFFICIENT and writes nothing")
    void walletFloorIsEnforced() {
        Long memberId = createMember("Nafis Iqbal", "+8801533770210", 0, 50);
        Long sessionId = floor.runningSessionOn(stationId, shiftId, 2, PS5_HALF_HOUR, 0);
        attach(memberId, sessionId);

        ResponseEntity<JsonNode> response = post("/api/v1/payments",
                Map.of("target", Map.of("sessionId", sessionId),
                        "splits", List.of(Map.of("method", "WALLET", "amount", 160))),
                withKey(UUID.randomUUID().toString()));

        assertErrorEnvelope(response, 409, "WALLET_INSUFFICIENT");
        assertThat(countOf("transactions")).isZero();
        assertThat(walletOf(memberId)).isEqualTo(50);
    }

    @Test
    @DisplayName("a wallet tender draws the balance down inside the same transaction")
    void walletSpendMovesTheLedger() {
        Long memberId = createMember("Rifat Hasan", "+8801712448190", 0, 400);
        Long sessionId = floor.runningSessionOn(stationId, shiftId, 2, PS5_HALF_HOUR, 0);
        attach(memberId, sessionId);

        long txId = settle(Map.of("target", Map.of("sessionId", sessionId),
                "splits", List.of(Map.of("method", "WALLET", "amount", 160))))
                .get("transactionId").asLong();

        assertThat(walletOf(memberId)).isEqualTo(240);
        List<Map<String, Object>> spent = jdbc.queryForList("SELECT delta, kind, ref_tx_id FROM "
                + "wallet_ledger WHERE member_id = ?", memberId);
        assertThat(spent).hasSize(1);
        assertThat(spent.get(0)).containsEntry("delta", -160).containsEntry("kind", "SPEND")
                .containsEntry("ref_tx_id", txId);
    }

    @Test
    @DisplayName("a seat that owes nothing has nothing to settle")
    void nothingToSettle() {
        Long sessionId = floor.runningSessionOn(stationId, shiftId, 0, PS5_HALF_HOUR, 0);
        floor.prepaidBlocksOn(sessionId, 2, PS5_HALF_HOUR, 4_207L);

        ResponseEntity<JsonNode> response = post("/api/v1/payments",
                Map.of("target", Map.of("sessionId", sessionId), "splits", List.of()),
                withKey(UUID.randomUUID().toString()));

        assertErrorEnvelope(response, 400, "VALIDATION_FAILED");
        assertThat(countOf("transactions")).isZero();
    }

    @Test
    @DisplayName("a payment names exactly one target")
    void exactlyOneTarget() {
        Long sessionId = floor.runningSessionOn(stationId, shiftId, 1, PS5_HALF_HOUR, 0);
        Long cartId = counterCart(createItem("Doritos", 90, 20), 1);

        assertErrorEnvelope(post("/api/v1/payments",
                Map.of("target", Map.of("sessionId", sessionId, "cartId", cartId),
                        "splits", List.of(Map.of("method", "CASH", "amount", 170))),
                withKey(UUID.randomUUID().toString())), 400, "VALIDATION_FAILED");
        assertErrorEnvelope(post("/api/v1/payments",
                Map.of("target", Map.of(), "splits", List.of()),
                withKey(UUID.randomUUID().toString())), 400, "VALIDATION_FAILED");
        assertThat(countOf("transactions")).isZero();
    }

    @Test
    @DisplayName("taking money needs an open shift on the terminal")
    void noShiftNoMoney() {
        Long sessionId = floor.runningSessionOn(stationId, shiftId, 1, PS5_HALF_HOUR, 0);
        jdbc.update("UPDATE shifts SET closed_at = now() WHERE id = ?", shiftId);

        ResponseEntity<JsonNode> response = post("/api/v1/payments",
                Map.of("target", Map.of("sessionId", sessionId),
                        "splits", List.of(Map.of("method", "CASH", "amount", 80))),
                withKey(UUID.randomUUID().toString()));

        assertErrorEnvelope(response, 409, "CONFLICT");
        assertThat(countOf("transactions")).isZero();
    }

    @Test
    @DisplayName("settling needs a signed-in operator")
    void anonymousIsRejected() {
        assertThat(post("/api/v1/payments", Map.of("target", Map.of("cartId", 1)), null)
                .getStatusCode().value()).isEqualTo(401);
    }

    // ---- helpers -----------------------------------------------------------------------------

    private JsonNode settle(Map<String, Object> body) {
        ResponseEntity<JsonNode> response =
                post("/api/v1/payments", body, withKey(UUID.randomUUID().toString()));
        assertThat(response.getStatusCode()).as("settle failed: %s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private HttpHeaders withKey(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(staff);
        headers.add("Idempotency-Key", key);
        return headers;
    }

    private Map<String, Object> transaction(long txId) {
        return jdbc.queryForMap("SELECT gaming_amount, fnb_amount, tournament_amount, "
                + "booking_amount, points_redeemed, points_earned, total_due, shift_id, session_id, "
                + "cart_id, member_id, voided FROM transactions WHERE id = ?", txId);
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

    /** The venue day the ids are numbered against — the same {@code ddMM} the server formats. */
    private String dayMonth() {
        return jdbc.queryForObject(
                "SELECT to_char(now() AT TIME ZONE 'Asia/Dhaka', 'DDMM')", String.class);
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
        addLine(cartId, itemId, qty);
        return cartId;
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
