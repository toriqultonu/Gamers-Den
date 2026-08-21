package dev.gamersden.catalog.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamersden.support.AbstractApiIntegrationTest;
import dev.gamersden.support.FloorFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /carts} and {@code PUT /carts/{id}/lines} — the till. Three things are load-bearing
 * and each has its own test: the line is <em>set</em> rather than incremented (so 0 removes and a
 * retry is harmless), the unit price is a snapshot, and {@code OUT_OF_STOCK} measures against the
 * shelf minus what other open carts already hold — because stock itself does not move until
 * settle (B10).
 */
class CartLinesIT extends AbstractApiIntegrationTest {

    private static final String CASHIER_PIN = "4321";

    private FloorFixtures floor;

    @BeforeEach
    void seedFloorFixtures() {
        floor = new FloorFixtures(jdbc);
    }

    // ---- opening a cart ---------------------------------------------------------------------

    @Test
    void aCounterCartOpensEmptyAndCarriesNoSession() {
        ResponseEntity<JsonNode> opened = post("/api/v1/carts", Map.of("type", "COUNTER"), adminBearer());

        assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(opened.getBody().get("type").asText()).isEqualTo("COUNTER");
        assertThat(opened.getBody().get("settled").asBoolean()).isFalse();
        assertThat(opened.getBody().get("total").asInt()).isZero();
        assertThat(opened.getBody().get("lines")).isEmpty();
        // non_null inclusion: a counter cart has no session at all.
        assertThat(opened.getBody().has("sessionId")).isFalse();
    }

    @Test
    void aSeatGetsOneCartForItsWholeLifeAndAskingAgainReturnsIt() {
        Long sessionId = liveSession();

        ResponseEntity<JsonNode> first = post("/api/v1/carts",
                Map.of("type", "SESSION", "sessionId", sessionId), adminBearer());
        ResponseEntity<JsonNode> again = post("/api/v1/carts",
                Map.of("type", "SESSION", "sessionId", sessionId), adminBearer());

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody().get("sessionId").asLong()).isEqualTo(sessionId);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody().get("id").asLong()).isEqualTo(first.getBody().get("id").asLong());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM carts", Integer.class)).isEqualTo(1);
    }

    @Test
    void aCartCannotHangOffAnUnknownOrClosedSession() {
        assertErrorEnvelope(post("/api/v1/carts",
                Map.of("type", "SESSION", "sessionId", 999999), adminBearer()), 404, "NOT_FOUND");

        Long stationId = createStation("PS5-01");
        Long closed = floor.closedSessionOn(stationId, floor.openShift(adminId, "T9"));
        assertErrorEnvelope(post("/api/v1/carts",
                Map.of("type", "SESSION", "sessionId", closed), adminBearer()), 404, "NOT_FOUND");
    }

    @Test
    void theTwoCartShapesAreNotInterchangeable() {
        Long sessionId = liveSession();

        assertErrorEnvelope(post("/api/v1/carts",
                Map.of("type", "COUNTER", "sessionId", sessionId), adminBearer()),
                400, "VALIDATION_FAILED");
        assertErrorEnvelope(post("/api/v1/carts", Map.of("type", "SESSION"), adminBearer()),
                400, "VALIDATION_FAILED");
        assertErrorEnvelope(post("/api/v1/carts", Map.of("type", "TAB"), adminBearer()),
                400, "VALIDATION_FAILED");
    }

    // ---- lines ------------------------------------------------------------------------------

    @Test
    void aLineIsUpsertedThenRemovedByQtyZero() {
        Long coke = createItem("Coke", 60, 24);
        Long cart = counterCart();

        JsonNode added = putLine(cart, coke, 2).getBody();
        assertThat(added.get("lines")).hasSize(1);
        assertThat(added.get("lines").get(0).get("qty").asInt()).isEqualTo(2);
        assertThat(added.get("lines").get(0).get("unitPrice").asInt()).isEqualTo(60);
        assertThat(added.get("lines").get(0).get("lineTotal").asInt()).isEqualTo(120);
        assertThat(added.get("total").asInt()).isEqualTo(120);

        JsonNode raised = putLine(cart, coke, 5).getBody();
        assertThat(raised.get("lines")).hasSize(1);            // upsert, not a second row
        assertThat(raised.get("lines").get(0).get("qty").asInt()).isEqualTo(5);
        assertThat(raised.get("total").asInt()).isEqualTo(300);
        assertThat(lineCount(cart)).isEqualTo(1);

        JsonNode removed = putLine(cart, coke, 0).getBody();
        assertThat(removed.get("lines")).isEmpty();
        assertThat(removed.get("total").asInt()).isZero();
        assertThat(lineCount(cart)).isZero();
    }

    @Test
    void removingALineThatIsNotThereIsANoOpNotAnError() {
        Long coke = createItem("Coke", 60, 24);
        Long cart = counterCart();

        assertThat(putLine(cart, coke, 0).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lineCount(cart)).isZero();
    }

    @Test
    void severalItemsSumIntoTheCartTotal() {
        Long coke = createItem("Coke", 60, 24);
        Long wrap = createItem("Wrap", 180, 5);
        Long cart = counterCart();

        putLine(cart, coke, 2);
        JsonNode both = putLine(cart, wrap, 1).getBody();

        assertThat(both.get("lines")).hasSize(2);
        assertThat(both.get("total").asInt()).isEqualTo(300);
    }

    @Test
    void theUnitPriceIsSnapshottedWhenTheLineOpens() {
        Long coke = createItem("Coke", 60, 24);
        Long cart = counterCart();
        putLine(cart, coke, 2);

        patch("/api/v1/items/" + coke, Map.of("price", 80), adminBearer());

        // Changing the quantity keeps the snapshot: the bill on screen must not move.
        JsonNode raised = putLine(cart, coke, 3).getBody();
        assertThat(raised.get("lines").get(0).get("unitPrice").asInt()).isEqualTo(60);
        assertThat(raised.get("total").asInt()).isEqualTo(180);

        // A line opened after the edit takes the new price.
        Long second = counterCart();
        assertThat(putLine(second, coke, 1).getBody().get("lines").get(0).get("unitPrice").asInt())
                .isEqualTo(80);
    }

    // ---- OUT_OF_STOCK -----------------------------------------------------------------------

    @Test
    void askingForMoreThanTheShelfHoldsIs409OutOfStock() {
        Long coke = createItem("Coke", 60, 3);
        Long cart = counterCart();

        ResponseEntity<JsonNode> refused = putLine(cart, coke, 4);

        assertErrorEnvelope(refused, 409, "OUT_OF_STOCK");
        assertThat(refused.getBody().get("error").get("details").get("available").asInt()).isEqualTo(3);
        assertThat(refused.getBody().get("error").get("details").get("requested").asInt()).isEqualTo(4);
        assertThat(lineCount(cart)).isZero();

        assertThat(putLine(cart, coke, 3).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void stockHeldOnAnotherOpenCartIsNotAvailableToThisOne() {
        Long coke = createItem("Coke", 60, 5);
        Long theirs = counterCart();
        Long mine = counterCart();
        putLine(theirs, coke, 4);

        assertErrorEnvelope(putLine(mine, coke, 2), 409, "OUT_OF_STOCK");
        assertThat(putLine(mine, coke, 1).getStatusCode()).isEqualTo(HttpStatus.OK);

        // The menu counts down with the guard, so the disabled card and the refusal agree.
        JsonNode card = get("/api/v1/items/" + coke, adminBearer()).getBody();
        assertThat(card.get("stock").asInt()).isEqualTo(5);   // nothing decremented until settle
        assertThat(card.get("available").asInt()).isZero();
        assertThat(card.get("outOfStock").asBoolean()).isTrue();
    }

    @Test
    void raisingAnExistingLineOnlyCountsTheOtherCartsHolding() {
        Long coke = createItem("Coke", 60, 5);
        Long cart = counterCart();
        putLine(cart, coke, 4);

        // Its own 4 must not be counted twice — the whole shelf is still reachable.
        assertThat(putLine(cart, coke, 5).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertErrorEnvelope(putLine(cart, coke, 6), 409, "OUT_OF_STOCK");
    }

    @Test
    void aSettledCartFreesItsHoldForEveryoneElse() {
        Long coke = createItem("Coke", 60, 5);
        Long theirs = counterCart();
        putLine(theirs, coke, 5);
        Long mine = counterCart();
        assertErrorEnvelope(putLine(mine, coke, 1), 409, "OUT_OF_STOCK");

        floor.settleCart(theirs);

        // Settle is B10's job to decrement the column; the hold itself is gone the moment the
        // cart closes, which is what this endpoint measures.
        assertThat(putLine(mine, coke, 5).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---- closed doors -----------------------------------------------------------------------

    @Test
    void aSettledCartTakesNoMoreLines() {
        Long coke = createItem("Coke", 60, 24);
        Long cart = counterCart();
        putLine(cart, coke, 1);
        floor.settleCart(cart);

        assertErrorEnvelope(putLine(cart, coke, 2), 409, "CONFLICT");
        assertThat(qtyOf(cart, coke)).isEqualTo(1);
    }

    @Test
    void anItemOffTheMenuCannotBeAddedButAnOpenLineCanStillBeRemoved() {
        Long coke = createItem("Coke", 60, 24);
        Long cart = counterCart();
        putLine(cart, coke, 2);
        patch("/api/v1/items/" + coke, Map.of("active", false), adminBearer());

        assertErrorEnvelope(putLine(cart, coke, 3), 400, "VALIDATION_FAILED");
        assertThat(putLine(cart, coke, 0).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lineCount(cart)).isZero();
    }

    @Test
    void anUnknownCartOrItemIs404AndANegativeQtyIs400() {
        Long coke = createItem("Coke", 60, 24);
        Long cart = counterCart();

        assertErrorEnvelope(putLine(999999L, coke, 1), 404, "NOT_FOUND");
        assertErrorEnvelope(putLine(cart, 999999L, 1), 404, "NOT_FOUND");
        assertErrorEnvelope(putLine(cart, coke, -1), 400, "VALIDATION_FAILED");
    }

    @Test
    void theTillIsOpenToEveryOperatorEvenThoughTheMenuIsNot() {
        Long coke = createItem("Coke", 60, 24);
        HttpHeaders cashier = cashierBearer();

        ResponseEntity<JsonNode> opened =
                post("/api/v1/carts", Map.of("type", "COUNTER"), cashier);
        assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> line = put(
                "/api/v1/carts/" + opened.getBody().get("id").asLong() + "/lines",
                Map.of("itemId", coke, "qty", 2), cashier);
        assertThat(line.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(line.getBody().get("total").asInt()).isEqualTo(120);
    }

    @Test
    void aCartLineNeedsNoIdempotencyKey() {
        // PUT sets the line rather than incrementing it, so it is deliberately off the §5.2 list.
        Long coke = createItem("Coke", 60, 24);
        Long cart = counterCart();

        assertThat(putLine(cart, coke, 2).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(putLine(cart, coke, 2).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(qtyOf(cart, coke)).isEqualTo(2);
    }

    // ---- helpers ----------------------------------------------------------------------------

    private ResponseEntity<JsonNode> putLine(Long cartId, Long itemId, int qty) {
        return put("/api/v1/carts/" + cartId + "/lines",
                Map.of("itemId", itemId, "qty", qty), adminBearer());
    }

    private Long counterCart() {
        ResponseEntity<JsonNode> opened =
                post("/api/v1/carts", Map.of("type", "COUNTER"), adminBearer());
        assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return opened.getBody().get("id").asLong();
    }

    private Long createItem(String name, int price, int stock) {
        ResponseEntity<JsonNode> created = post("/api/v1/items",
                Map.of("name", name, "category", "BEVERAGE", "price", price, "stock", stock),
                adminBearer());
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody().get("id").asLong();
    }

    private Long createStation(String name) {
        ResponseEntity<JsonNode> created = post("/api/v1/stations",
                Map.of("name", name, "consoleType", "PS5"), adminBearer());
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody().get("id").asLong();
    }

    private Long liveSession() {
        Long stationId = createStation("PS5-01");
        return floor.runningSessionOn(stationId, floor.openShift(adminId, "T9"), 2, 80, 0);
    }

    private int lineCount(Long cartId) {
        return jdbc.queryForObject("SELECT count(*) FROM cart_lines WHERE cart_id = ?",
                Integer.class, cartId);
    }

    private int qtyOf(Long cartId, Long itemId) {
        return jdbc.queryForObject("SELECT qty FROM cart_lines WHERE cart_id = ? AND item_id = ?",
                Integer.class, cartId, itemId);
    }

    private HttpHeaders cashierBearer() {
        ResponseEntity<JsonNode> created = post("/api/v1/staff",
                Map.of("name", "Rafi", "role", "CASHIER", "pin", CASHIER_PIN), adminBearer());
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return bearerFor(created.getBody().get("id").asLong(), CASHIER_PIN);
    }
}
