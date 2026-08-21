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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /sessions/{id}/bill} end to end against a real Postgres: the three lookup doors, the
 * real rows behind them, and the JSON the FE's bill panel is written against.
 *
 * <p>{@link dev.gamersden.billing.domain.BillTest} owns the arithmetic; what is proved here is the
 * wiring — that "unbilled" really means {@code session_blocks.paid_tx_id IS NULL}, that "F&amp;B"
 * really means the unsettled cart, and that the member's points come off the member row.
 */
class SessionBillIT extends AbstractApiIntegrationTest {

    /** The transaction a booking or play-ticket sale left on prepaid blocks (invariant §5.9). */
    private static final long PREPAID_SALE_TX = 4_207L;

    /** The transaction a mid-session settle left on the blocks it paid for (B10). */
    private static final long MID_SESSION_TX = 9_001L;

    private static final int PS5_HALF_HOUR = 80;

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
    @DisplayName("a seat with no time, no cart and no member has an empty bill")
    void emptyBill() {
        Long sessionId = floor.runningSessionOn(stationId, shiftId, 0, PS5_HALF_HOUR, 0);

        JsonNode bill = billOf(sessionId);

        assertThat(bill.get("sessionId").asLong()).isEqualTo(sessionId);
        assertThat(bill.get("stationId").asLong()).isEqualTo(stationId);
        assertThat(bill.get("lines")).isEmpty();
        assertThat(bill.get("gamingDue").asInt()).isZero();
        assertThat(bill.get("fnbDue").asInt()).isZero();
        assertThat(bill.get("tournamentDue").asInt()).isZero();
        assertThat(bill.get("netTotal").asInt()).isZero();
        assertThat(bill.get("prepaidCredit").asInt()).isZero();
        assertThat(bill.get("pointsRedeemable").asInt()).isZero();
        assertThat(bill.get("settled").asBoolean()).isTrue();
        assertThat(bill.get("serverTime").asText()).isNotBlank();
        // No member attached: the field is omitted, not sent as null.
        assertThat(bill.has("memberId")).isFalse();
    }

    @Test
    @DisplayName("unpaid blocks are the gaming line, at the rate they were sold at")
    void gamingFromUnpaidBlocks() {
        Long sessionId = floor.runningSessionOn(stationId, shiftId, 3, PS5_HALF_HOUR, 0);

        JsonNode bill = billOf(sessionId);

        assertThat(bill.get("gamingDue").asInt()).isEqualTo(240);
        assertThat(bill.get("billableBlocks").asInt()).isEqualTo(3);
        assertThat(bill.get("netTotal").asInt()).isEqualTo(240);
        assertThat(bill.get("settled").asBoolean()).isFalse();
        assertThat(bill.get("lines")).hasSize(1);
        JsonNode line = bill.get("lines").get(0);
        assertThat(line.get("kind").asText()).isEqualTo("GAMING");
        assertThat(line.get("qty").asInt()).isEqualTo(3);
        assertThat(line.get("unitPrice").asInt()).isEqualTo(PS5_HALF_HOUR);
        assertThat(line.get("amount").asInt()).isEqualTo(240);
    }

    @Test
    @DisplayName("after a mid-session settle only the blocks bought since are billed")
    void unbilledBlocksOnlyAfterAMidSessionSettle() {
        Long sessionId = floor.runningSessionOn(stationId, shiftId, 2, PS5_HALF_HOUR, 0);
        floor.markBlocksPaid(sessionId, MID_SESSION_TX);
        // The session kept running and bought another half hour after the settle.
        jdbc.update("INSERT INTO session_blocks (session_id, price) VALUES (?, ?)",
                sessionId, PS5_HALF_HOUR);

        JsonNode bill = billOf(sessionId);

        assertThat(bill.get("gamingDue").asInt()).isEqualTo(80);
        assertThat(bill.get("billableBlocks").asInt()).isEqualTo(1);
        assertThat(bill.get("prepaidBlocks").asInt()).isEqualTo(2);
        assertThat(bill.get("prepaidCredit").asInt()).isEqualTo(160);
        assertThat(bill.get("netTotal").asInt()).isEqualTo(80);
        assertThat(bill.get("gamingValue").asInt()).isEqualTo(240);
    }

    @Test
    @DisplayName("prepaid blocks show as credit and never as due")
    void prepaidBlocksAreExcludedFromDue() {
        Long sessionId = floor.runningSessionOn(stationId, shiftId, 0, PS5_HALF_HOUR, 0);
        floor.prepaidBlocksOn(sessionId, 4, PS5_HALF_HOUR, PREPAID_SALE_TX);

        JsonNode bill = billOf(sessionId);

        assertThat(bill.get("lines")).isEmpty();
        assertThat(bill.get("gamingDue").asInt()).isZero();
        assertThat(bill.get("netTotal").asInt()).isZero();
        assertThat(bill.get("prepaidBlocks").asInt()).isEqualTo(4);
        assertThat(bill.get("prepaidCredit").asInt()).isEqualTo(320);
        assertThat(bill.get("settled").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("extra time on a prepaid seat is ordinary billable time")
    void extraTimeOnAPrepaidSeat() {
        Long sessionId = floor.runningSessionOn(stationId, shiftId, 1, PS5_HALF_HOUR, 0);
        floor.prepaidBlocksOn(sessionId, 4, PS5_HALF_HOUR, PREPAID_SALE_TX);

        JsonNode bill = billOf(sessionId);

        assertThat(bill.get("gamingDue").asInt()).isEqualTo(80);
        assertThat(bill.get("prepaidCredit").asInt()).isEqualTo(320);
        assertThat(bill.get("netTotal").asInt()).isEqualTo(80);
    }

    @Test
    @DisplayName("F&B comes from the unsettled cart and leaves the bill once it is settled")
    void fnbFromTheCart() {
        Long sessionId = floor.runningSessionOn(stationId, shiftId, 1, PS5_HALF_HOUR, 0);
        Long cartId = floor.unsettledCartOn(sessionId, "Pepsi 250ml", 60, 3);

        JsonNode bill = billOf(sessionId);

        assertThat(bill.get("fnbDue").asInt()).isEqualTo(180);
        assertThat(bill.get("netTotal").asInt()).isEqualTo(260);
        assertThat(bill.get("lines")).hasSize(2);
        JsonNode food = bill.get("lines").get(1);
        assertThat(food.get("kind").asText()).isEqualTo("FNB");
        assertThat(food.get("label").asText()).isEqualTo("Pepsi 250ml");
        assertThat(food.get("qty").asInt()).isEqualTo(3);
        assertThat(food.get("amount").asInt()).isEqualTo(180);

        floor.settleCart(cartId);

        JsonNode after = billOf(sessionId);
        assertThat(after.get("fnbDue").asInt()).isZero();
        assertThat(after.get("netTotal").asInt()).isEqualTo(80);
    }

    @Test
    @DisplayName("the attached member's points are quoted, capped at what is due")
    void pointsAreCappedAtTheBill() {
        Long memberId = createMember("Rifat Hasan", "+8801712448190", 5_000, 1_240);
        Long sessionId = floor.runningSessionOn(stationId, shiftId, 2, PS5_HALF_HOUR, 0);
        jdbc.update("UPDATE sessions SET member_id = ? WHERE id = ?", memberId, sessionId);

        JsonNode bill = billOf(sessionId);

        assertThat(bill.get("memberId").asLong()).isEqualTo(memberId);
        assertThat(bill.get("memberName").asText()).isEqualTo("Rifat Hasan");
        assertThat(bill.get("memberPoints").asInt()).isEqualTo(5_000);
        assertThat(bill.get("memberWallet").asInt()).isEqualTo(1_240);
        assertThat(bill.get("netTotal").asInt()).isEqualTo(160);
        assertThat(bill.get("pointsRedeemable").asInt()).isEqualTo(160);
    }

    @Test
    @DisplayName("a member with fewer points than the bill redeems only what they have")
    void pointsBelowTheBill() {
        Long memberId = createMember("Nafis Iqbal", "+8801533770210", 86, 0);
        Long sessionId = floor.runningSessionOn(stationId, shiftId, 2, PS5_HALF_HOUR, 0);
        jdbc.update("UPDATE sessions SET member_id = ? WHERE id = ?", memberId, sessionId);

        assertThat(billOf(sessionId).get("pointsRedeemable").asInt()).isEqualTo(86);
    }

    @Test
    @DisplayName("quoting a bill moves nothing — no block, no cart, no balance")
    void quotingIsReadOnly() {
        Long memberId = createMember("Rifat Hasan", "+8801712448190", 5_000, 1_240);
        Long sessionId = floor.runningSessionOn(stationId, shiftId, 2, PS5_HALF_HOUR, 0);
        jdbc.update("UPDATE sessions SET member_id = ? WHERE id = ?", memberId, sessionId);
        Long cartId = floor.unsettledCartOn(sessionId, "Pepsi 250ml", 60, 3);

        billOf(sessionId);
        billOf(sessionId);

        assertThat(floor.blockPricesOf(sessionId)).containsExactly(PS5_HALF_HOUR, PS5_HALF_HOUR);
        assertThat(jdbc.queryForObject("SELECT points FROM members WHERE id = ?",
                Integer.class, memberId)).isEqualTo(5_000);
        assertThat(jdbc.queryForObject("SELECT settled FROM carts WHERE id = ?",
                Boolean.class, cartId)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM points_ledger", Integer.class)).isZero();
    }

    @Test
    @DisplayName("a closed seat still has a readable bill")
    void closedSessionStillReads() {
        Long sessionId = floor.closedSessionOn(stationId, shiftId);

        JsonNode bill = billOf(sessionId);

        assertThat(bill.get("sessionState").asText()).isEqualTo("CLOSED");
        assertThat(bill.get("netTotal").asInt()).isZero();
    }

    @Test
    @DisplayName("an unknown session is 404 in the error envelope")
    void unknownSession() {
        assertErrorEnvelope(get("/api/v1/sessions/999999/bill", staff), 404, "NOT_FOUND");
    }

    @Test
    @DisplayName("the bill needs a signed-in operator")
    void anonymousIsRejected() {
        Long sessionId = floor.runningSessionOn(stationId, shiftId, 1, PS5_HALF_HOUR, 0);

        assertThat(get("/api/v1/sessions/" + sessionId + "/bill", null).getStatusCode().value())
                .isEqualTo(401);
    }

    // ---- helpers -----------------------------------------------------------------------------

    private JsonNode billOf(Long sessionId) {
        ResponseEntity<JsonNode> response = get("/api/v1/sessions/" + sessionId + "/bill", staff);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private Long createStation(String name, String consoleType) {
        ResponseEntity<JsonNode> created = post("/api/v1/stations",
                Map.of("name", name, "consoleType", consoleType), adminBearer());
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody().get("id").asLong();
    }

    private Long createMember(String name, String phone, int points, int wallet) {
        return jdbc.queryForObject(
                "INSERT INTO members (name, phone, points, wallet) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, name, phone, points, wallet);
    }
}
