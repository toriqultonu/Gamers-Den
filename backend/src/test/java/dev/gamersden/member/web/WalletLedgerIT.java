package dev.gamersden.member.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamersden.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /members/{id}/wallet/topup} and {@code /wallet/redeem-points} — money.
 *
 * <p>Three invariants are asserted on every path here: the balance column and its ledger move
 * <strong>together, in one transaction</strong> (a rejected movement writes neither); the routes
 * are on the idempotency list, so a retry replays instead of crediting twice; and a wallet or
 * points balance never goes negative.
 */
class WalletLedgerIT extends AbstractApiIntegrationTest {

    private HttpHeaders staff;
    private long memberId;

    @BeforeEach
    void seedMember() {
        jdbc.update("DELETE FROM idempotency_keys");
        staff = adminBearer();
        ResponseEntity<JsonNode> created = post("/api/v1/members",
                Map.of("name", "Rafi Ahmed", "phone", "01712345678"), staff);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        memberId = created.getBody().get("id").asLong();
    }

    // ---- top-up -----------------------------------------------------------------------------

    @Test
    @DisplayName("a top-up writes the TOPUP ledger row and the column in the same transaction")
    void topupWritesLedgerAndColumn() {
        JsonNode after = ok(topup(500, "CASH", null, key()));

        assertThat(after.get("wallet").asInt()).isEqualTo(500);
        assertThat(walletOf(memberId)).isEqualTo(500);
        assertThat(walletLedger()).containsExactly(Map.entry("TOPUP", 500));
        assertThat(pointsLedger()).isEmpty();
    }

    @Test
    void topupsAccumulateAndEveryOneLeavesItsOwnLedgerRow() {
        ok(topup(500, "CASH", null, key()));
        ok(topup(300, "BKASH", "TRX9931", key()));

        assertThat(walletOf(memberId)).isEqualTo(800);
        assertThat(walletLedger())
                .containsExactly(Map.entry("TOPUP", 500), Map.entry("TOPUP", 300));
    }

    @Test
    @DisplayName("the same Idempotency-Key never credits the wallet twice")
    void topupReplaysInsteadOfDoubleCrediting() {
        HttpHeaders key = key();

        ResponseEntity<JsonNode> first = topup(500, "CASH", null, key);
        ResponseEntity<JsonNode> retry = topup(500, "CASH", null, key);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
        assertThat(retry.getBody()).isEqualTo(first.getBody());
        assertThat(walletOf(memberId)).isEqualTo(500);
        assertThat(walletLedger()).containsExactly(Map.entry("TOPUP", 500));
    }

    @Test
    @DisplayName("a different amount under a used key is 409 — and moves nothing")
    void aMutatedBodyUnderTheSameKeyIsRejected() {
        HttpHeaders key = key();
        ok(topup(500, "CASH", null, key));

        assertErrorEnvelope(topup(900, "CASH", null, key), 409, "IDEMPOTENCY_REPLAY");
        assertThat(walletOf(memberId)).isEqualTo(500);
        assertThat(walletLedger()).hasSize(1);
    }

    @Test
    @DisplayName("the wallet routes are on the idempotency list — no key, no money")
    void aTopupWithoutAKeyIsRejected() {
        assertErrorEnvelope(post(path("topup"), body(500, "CASH", null), staff),
                400, "VALIDATION_FAILED");
        assertThat(walletOf(memberId)).isZero();
        assertThat(walletLedger()).isEmpty();
    }

    @Test
    void aTopupMustBeAPositiveAmountTenderedSomehow() {
        assertErrorEnvelope(topup(0, "CASH", null, key()), 400, "VALIDATION_FAILED");
        assertErrorEnvelope(topup(-100, "CASH", null, key()), 400, "VALIDATION_FAILED");
        assertErrorEnvelope(topup(500, null, null, key()), 400, "VALIDATION_FAILED");
        // A wallet cannot fund itself: WALLET is not a top-up method.
        assertErrorEnvelope(topup(500, "WALLET", null, key()), 400, "VALIDATION_FAILED");
        assertThat(walletOf(memberId)).isZero();
        assertThat(walletLedger()).isEmpty();
    }

    @Test
    void toppingUpAnUnknownMemberIsTheNotFoundEnvelope() {
        ResponseEntity<JsonNode> missing = post("/api/v1/members/999999/wallet/topup",
                body(500, "CASH", null), key());

        assertErrorEnvelope(missing, 404, "NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wallet_ledger", Integer.class)).isZero();
    }

    // ---- redeeming points -------------------------------------------------------------------

    @Test
    @DisplayName("1 point = BDT 1: both ledgers and both columns move in one transaction")
    void redeemMovesPointsIntoTheWallet() {
        givePoints(120);
        ok(topup(200, "CASH", null, key()));

        JsonNode after = ok(redeem(50, key()));

        assertThat(after.get("points").asInt()).isEqualTo(70);
        assertThat(after.get("wallet").asInt()).isEqualTo(250);
        assertThat(pointsOf(memberId)).isEqualTo(70);
        assertThat(walletOf(memberId)).isEqualTo(250);
        assertThat(pointsLedger()).containsExactly(Map.entry("REDEEM_WALLET", -50));
        assertThat(walletLedger()).containsExactly(
                Map.entry("TOPUP", 200), Map.entry("POINTS_CONVERSION", 50));
    }

    @Test
    void theWholeBalanceCanGoAtOnce() {
        givePoints(120);

        ok(redeem(120, key()));

        assertThat(pointsOf(memberId)).isZero();
        assertThat(walletOf(memberId)).isEqualTo(120);
    }

    @Test
    @DisplayName("redeeming more points than the member holds is 409 and writes nothing")
    void redeemBelowBalanceIsRejected() {
        givePoints(40);

        ResponseEntity<JsonNode> refused = redeem(41, key());

        assertErrorEnvelope(refused, 409, "INSUFFICIENT_POINTS");
        JsonNode details = refused.getBody().get("error").get("details");
        assertThat(details.get("points").asInt()).isEqualTo(40);
        assertThat(details.get("requested").asInt()).isEqualTo(41);
        assertThat(pointsOf(memberId)).isEqualTo(40);
        assertThat(walletOf(memberId)).isZero();
        assertThat(pointsLedger()).isEmpty();
        assertThat(walletLedger()).isEmpty();
    }

    @Test
    void aMemberWithNoPointsCannotRedeemAtAll() {
        assertErrorEnvelope(redeem(1, key()), 409, "INSUFFICIENT_POINTS");
        assertThat(walletLedger()).isEmpty();
    }

    @Test
    @DisplayName("the same Idempotency-Key never converts the same points twice")
    void redeemReplaysInsteadOfConvertingTwice() {
        givePoints(120);
        HttpHeaders key = key();

        ResponseEntity<JsonNode> first = redeem(50, key);
        ResponseEntity<JsonNode> retry = redeem(50, key);

        assertThat(retry.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
        assertThat(retry.getBody()).isEqualTo(first.getBody());
        assertThat(pointsOf(memberId)).isEqualTo(70);
        assertThat(walletOf(memberId)).isEqualTo(50);
        assertThat(pointsLedger()).hasSize(1);
        assertThat(walletLedger()).hasSize(1);
    }

    @Test
    void aRedemptionWithoutAKeyIsRejected() {
        givePoints(120);

        assertErrorEnvelope(post(path("redeem-points"), Map.of("points", 50), staff),
                400, "VALIDATION_FAILED");
        assertThat(pointsOf(memberId)).isEqualTo(120);
        assertThat(pointsLedger()).isEmpty();
    }

    // ---- the column is the ledger's running total ---------------------------------------------

    @Test
    @DisplayName("whatever the sequence, each column equals the sum of its ledger")
    void columnsStayEqualToTheirLedgers() {
        givePoints(200);
        ok(topup(500, "CASH", null, key()));
        ok(redeem(80, key()));
        ok(topup(120, "NAGAD", "TRX-4412", key()));
        assertErrorEnvelope(redeem(500, key()), 409, "INSUFFICIENT_POINTS");

        assertThat(walletOf(memberId)).isEqualTo(sumOf("wallet_ledger"));
        assertThat(walletOf(memberId)).isEqualTo(700);
        // The seeded points are a fixture, not a ledger movement, so only the redemption shows.
        assertThat(sumOf("points_ledger")).isEqualTo(-80);
        assertThat(pointsOf(memberId)).isEqualTo(120);
    }

    @Test
    @DisplayName("concurrent top-ups serialise on the member row — no lost update")
    void concurrentTopupsBothLand() throws Exception {
        List<HttpHeaders> keys = List.of(key(), key(), key(), key(), key());
        ExecutorService pool = Executors.newFixedThreadPool(keys.size());
        try {
            List<Callable<ResponseEntity<JsonNode>>> calls = keys.stream()
                    .map(key -> (Callable<ResponseEntity<JsonNode>>) () -> topup(100, "CASH", null, key))
                    .toList();
            for (Future<ResponseEntity<JsonNode>> result : pool.invokeAll(calls)) {
                assertThat(result.get().getStatusCode()).isEqualTo(HttpStatus.OK);
            }
        } finally {
            pool.shutdown();
        }

        assertThat(walletOf(memberId)).isEqualTo(500);
        assertThat(sumOf("wallet_ledger")).isEqualTo(500);
        assertThat(walletLedger()).hasSize(5);
    }

    // ---- helpers ------------------------------------------------------------------------------

    private ResponseEntity<JsonNode> topup(int amount, String method, String ref, HttpHeaders headers) {
        return post(path("topup"), body(amount, method, ref), headers);
    }

    private ResponseEntity<JsonNode> redeem(int points, HttpHeaders headers) {
        return post(path("redeem-points"), Map.of("points", points), headers);
    }

    private String path(String action) {
        return "/api/v1/members/" + memberId + "/wallet/" + action;
    }

    private static Map<String, Object> body(int amount, String method, String ref) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("amount", amount);
        body.put("method", method);
        body.put("paymentRef", ref);
        return body;
    }

    private static JsonNode ok(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private HttpHeaders key() {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(staff);
        headers.add("Idempotency-Key", UUID.randomUUID().toString());
        return headers;
    }

    /**
     * Points a member earned before this task existed. Earning is B10's (floor(due/20) at settle),
     * so the balance is seeded straight onto the column — the redemption under test is what has to
     * write its ledger.
     */
    private void givePoints(int points) {
        jdbc.update("UPDATE members SET points = ? WHERE id = ?", points, memberId);
    }

    private int walletOf(long id) {
        return jdbc.queryForObject("SELECT wallet FROM members WHERE id = ?", Integer.class, id);
    }

    private int pointsOf(long id) {
        return jdbc.queryForObject("SELECT points FROM members WHERE id = ?", Integer.class, id);
    }

    private List<Map.Entry<String, Integer>> walletLedger() {
        return ledger("wallet_ledger");
    }

    private List<Map.Entry<String, Integer>> pointsLedger() {
        return ledger("points_ledger");
    }

    /** One member's ledger, oldest first, as {@code kind -> delta} pairs. */
    private List<Map.Entry<String, Integer>> ledger(String table) {
        return jdbc.query("SELECT kind, delta FROM " + table + " WHERE member_id = ? ORDER BY id",
                (rs, row) -> Map.entry(rs.getString("kind"), rs.getInt("delta")), memberId);
    }

    private int sumOf(String table) {
        return jdbc.queryForObject(
                "SELECT COALESCE(sum(delta), 0) FROM " + table + " WHERE member_id = ?",
                Integer.class, memberId);
    }
}
