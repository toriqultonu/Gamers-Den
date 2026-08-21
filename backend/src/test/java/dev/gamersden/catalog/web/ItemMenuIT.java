package dev.gamersden.catalog.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamersden.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /items} — the menu every operator sells from and only Manager+ edits, plus the rule that
 * outranks the rest: <strong>stock never moves without an audit row</strong>. Every assertion on
 * {@code stock} here is paired with one on {@code stock_movements}.
 */
class ItemMenuIT extends AbstractApiIntegrationTest {

    private static final String MANAGER_PIN = "2222";
    private static final String CASHIER_PIN = "4321";

    /** Created on first use and reused — a second POST /staff with the same name is 409. */
    private HttpHeaders manager;
    private HttpHeaders cashier;

    @BeforeEach
    void forgetCachedStaff() {
        manager = null;
        cashier = null;
    }

    // ---- stock audit ----------------------------------------------------------------------

    @Test
    void anOpeningStockLandsAsAnInitialMovement() {
        Long id = createItem(Map.of("name", "Coke", "category", "BEVERAGE", "price", 60,
                "stock", 24, "reorderAt", 6));

        assertThat(stockOf(id)).isEqualTo(24);
        assertThat(movementsOf(id)).containsExactly(Map.entry("INITIAL", 24));
    }

    @Test
    void anItemCreatedWithNoStockGetsNoMovementAtAll() {
        Long id = createItem(Map.of("name", "Coke", "category", "BEVERAGE", "price", 60));

        assertThat(stockOf(id)).isZero();
        assertThat(movementsOf(id)).isEmpty();
    }

    @Test
    void aManualStocktakeUpWritesOneSignedAdjustRow() {
        Long id = createItem(Map.of("name", "Coke", "category", "BEVERAGE", "price", 60, "stock", 10));

        ResponseEntity<JsonNode> patched =
                patch("/api/v1/items/" + id, Map.of("stock", 18), managerBearer());

        assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patched.getBody().get("stock").asInt()).isEqualTo(18);
        assertThat(stockOf(id)).isEqualTo(18);
        assertThat(movementsOf(id))
                .containsExactly(Map.entry("INITIAL", 10), Map.entry("MANUAL_ADJUST", 8));
    }

    @Test
    void aManualStocktakeDownWritesANegativeAdjustRow() {
        Long id = createItem(Map.of("name", "Coke", "category", "BEVERAGE", "price", 60, "stock", 10));

        patch("/api/v1/items/" + id, Map.of("stock", 3), managerBearer());

        assertThat(stockOf(id)).isEqualTo(3);
        assertThat(movementsOf(id))
                .containsExactly(Map.entry("INITIAL", 10), Map.entry("MANUAL_ADJUST", -7));
    }

    @Test
    void everyAdjustRowNamesTheStaffWhoMadeIt() {
        Long id = createItem(Map.of("name", "Coke", "category", "BEVERAGE", "price", 60, "stock", 10));
        Long managerId = staffId("Tanvir", "MANAGER", MANAGER_PIN);

        patch("/api/v1/items/" + id, Map.of("stock", 12), bearerFor(managerId, MANAGER_PIN));

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT reason, delta, staff_id FROM stock_movements WHERE item_id = ? ORDER BY id", id);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("reason", "INITIAL").containsEntry("staff_id", adminId);
        assertThat(rows.get(1))
                .containsEntry("reason", "MANUAL_ADJUST")
                .containsEntry("delta", 2)
                .containsEntry("staff_id", managerId);
    }

    @Test
    void aPatchThatLeavesStockAloneWritesNoMovement() {
        Long id = createItem(Map.of("name", "Coke", "category", "BEVERAGE", "price", 60, "stock", 10));

        patch("/api/v1/items/" + id, Map.of("price", 70, "reorderAt", 4), managerBearer());
        patch("/api/v1/items/" + id, Map.of("stock", 10), managerBearer());

        assertThat(movementsOf(id)).containsExactly(Map.entry("INITIAL", 10));
        assertThat(itemJson(id).get("price").asInt()).isEqualTo(70);
    }

    // ---- role guards ----------------------------------------------------------------------

    @Test
    void aCashierReadsTheMenuButCannotWriteIt() {
        Long id = createItem(Map.of("name", "Coke", "category", "BEVERAGE", "price", 60, "stock", 10));
        HttpHeaders cashier = cashierBearer();

        assertThat(get("/api/v1/items", cashier).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/v1/items/" + id, cashier).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertErrorEnvelope(post("/api/v1/items",
                Map.of("name", "Pepsi", "category", "BEVERAGE", "price", 55), cashier), 403, "FORBIDDEN");
        assertErrorEnvelope(patch("/api/v1/items/" + id, Map.of("price", 10), cashier), 403, "FORBIDDEN");
        assertErrorEnvelope(patch("/api/v1/items/" + id, Map.of("stock", 999), cashier), 403, "FORBIDDEN");
        assertErrorEnvelope(delete("/api/v1/items/" + id, cashier), 403, "FORBIDDEN");

        // The refused writes left neither the column nor the audit behind.
        assertThat(stockOf(id)).isEqualTo(10);
        assertThat(movementsOf(id)).containsExactly(Map.entry("INITIAL", 10));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM items", Integer.class)).isEqualTo(1);
    }

    @Test
    void aManagerOwnsTheMenuUnlikeTheStationsAndPricingBar() {
        HttpHeaders manager = managerBearer();

        ResponseEntity<JsonNode> created = post("/api/v1/items",
                Map.of("name", "Chicken Wrap", "category", "FOOD", "price", 180, "stock", 12), manager);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(patch("/api/v1/items/" + created.getBody().get("id").asLong(),
                Map.of("price", 190), manager).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void noTokenAtAllIs401NotForbidden() {
        assertErrorEnvelope(get("/api/v1/items", null), 401, "UNAUTHORIZED");
    }

    // ---- CRUD ------------------------------------------------------------------------------

    @Test
    void aDuplicateNameIs409DuplicateName() {
        createItem(Map.of("name", "Coke", "category", "BEVERAGE", "price", 60));

        assertErrorEnvelope(post("/api/v1/items",
                Map.of("name", "Coke", "category", "SNACK", "price", 20), adminBearer()),
                409, "DUPLICATE_NAME");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM items", Integer.class)).isEqualTo(1);
    }

    @Test
    void renamingOntoATakenNameIs409DuplicateName() {
        createItem(Map.of("name", "Coke", "category", "BEVERAGE", "price", 60));
        Long other = createItem(Map.of("name", "Pepsi", "category", "BEVERAGE", "price", 55));

        assertErrorEnvelope(patch("/api/v1/items/" + other, Map.of("name", "Coke"), adminBearer()),
                409, "DUPLICATE_NAME");
    }

    @Test
    void theMenuReadsGroupedByCategoryAndFiltersOnActive() {
        createItem(Map.of("name", "Pepsi", "category", "BEVERAGE", "price", 55));
        createItem(Map.of("name", "Coke", "category", "BEVERAGE", "price", 60));
        Long wrap = createItem(Map.of("name", "Wrap", "category", "FOOD", "price", 180));
        patch("/api/v1/items/" + wrap, Map.of("active", false), adminBearer());

        JsonNode all = get("/api/v1/items", adminBearer()).getBody();
        assertThat(all).hasSize(3);
        assertThat(names(all)).containsExactly("Coke", "Pepsi", "Wrap");   // BEVERAGE before FOOD

        JsonNode onMenu = get("/api/v1/items?active=true", adminBearer()).getBody();
        assertThat(names(onMenu)).containsExactly("Coke", "Pepsi");

        JsonNode food = get("/api/v1/items?category=FOOD", adminBearer()).getBody();
        assertThat(names(food)).containsExactly("Wrap");
    }

    @Test
    void anItemWithNoHistoryIsDeletedOutright() {
        Long id = createItem(Map.of("name", "Coke", "category", "BEVERAGE", "price", 60));

        assertThat(delete("/api/v1/items/" + id, adminBearer()).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(get("/api/v1/items", adminBearer()).getBody()).isEmpty();
    }

    @Test
    void anItemWithStockHistoryIsDeactivatedSoTheAuditSurvives() {
        Long id = createItem(Map.of("name", "Coke", "category", "BEVERAGE", "price", 60, "stock", 10));

        assertThat(delete("/api/v1/items/" + id, adminBearer()).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(itemJson(id).get("active").asBoolean()).isFalse();
        assertThat(get("/api/v1/items?active=true", adminBearer()).getBody()).isEmpty();
        assertThat(movementsOf(id)).containsExactly(Map.entry("INITIAL", 10));
    }

    @Test
    void lowStockAndOutOfStockAreDerivedNotStored() {
        Long id = createItem(Map.of("name", "Coke", "category", "BEVERAGE", "price", 60,
                "stock", 10, "reorderAt", 6));
        assertThat(itemJson(id).get("lowStock").asBoolean()).isFalse();

        patch("/api/v1/items/" + id, Map.of("stock", 6), managerBearer());
        assertThat(itemJson(id).get("lowStock").asBoolean()).isTrue();
        assertThat(itemJson(id).get("outOfStock").asBoolean()).isFalse();

        patch("/api/v1/items/" + id, Map.of("stock", 0), managerBearer());
        assertThat(itemJson(id).get("outOfStock").asBoolean()).isTrue();
        assertThat(itemJson(id).get("available").asInt()).isZero();
    }

    @Test
    void anUnknownItemIs404() {
        assertErrorEnvelope(get("/api/v1/items/999999", adminBearer()), 404, "NOT_FOUND");
        assertErrorEnvelope(patch("/api/v1/items/999999", Map.of("price", 10), adminBearer()),
                404, "NOT_FOUND");
        assertErrorEnvelope(delete("/api/v1/items/999999", adminBearer()), 404, "NOT_FOUND");
    }

    @Test
    void aBlankNameUnknownCategoryOrNegativeMoneyIs400() {
        assertErrorEnvelope(post("/api/v1/items",
                Map.of("name", "  ", "category", "BEVERAGE", "price", 60), adminBearer()),
                400, "VALIDATION_FAILED");
        assertErrorEnvelope(post("/api/v1/items",
                Map.of("name", "Coke", "category", "DRINKS", "price", 60), adminBearer()),
                400, "VALIDATION_FAILED");
        assertErrorEnvelope(post("/api/v1/items",
                Map.of("name", "Coke", "category", "BEVERAGE", "price", -1), adminBearer()),
                400, "VALIDATION_FAILED");
        assertErrorEnvelope(post("/api/v1/items",
                Map.of("name", "Coke", "category", "BEVERAGE", "price", 60, "stock", -5), adminBearer()),
                400, "VALIDATION_FAILED");
    }

    // ---- helpers ---------------------------------------------------------------------------

    private Long createItem(Map<String, Object> body) {
        ResponseEntity<JsonNode> created = post("/api/v1/items", body, adminBearer());
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody().get("id").asLong();
    }

    private JsonNode itemJson(Long id) {
        return get("/api/v1/items/" + id, adminBearer()).getBody();
    }

    private int stockOf(Long id) {
        return jdbc.queryForObject("SELECT stock FROM items WHERE id = ?", Integer.class, id);
    }

    /** The append-only audit for one item, oldest first, as {@code reason -> delta} pairs. */
    private List<Map.Entry<String, Integer>> movementsOf(Long id) {
        return jdbc.query("SELECT reason, delta FROM stock_movements WHERE item_id = ? ORDER BY id",
                (rs, row) -> Map.entry(rs.getString("reason"), rs.getInt("delta")), id);
    }

    private static List<String> names(JsonNode items) {
        return items.findValuesAsText("name");
    }

    private Long staffId(String name, String role, String pin) {
        Map<String, Object> body = new HashMap<>(Map.of("name", name, "role", role, "pin", pin));
        ResponseEntity<JsonNode> created = post("/api/v1/staff", body, adminBearer());
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody().get("id").asLong();
    }

    private HttpHeaders managerBearer() {
        if (manager == null) {
            manager = bearerFor(staffId("Tanvir", "MANAGER", MANAGER_PIN), MANAGER_PIN);
        }
        return manager;
    }

    private HttpHeaders cashierBearer() {
        if (cashier == null) {
            cashier = bearerFor(staffId("Rafi", "CASHIER", CASHIER_PIN), CASHIER_PIN);
        }
        return cashier;
    }
}
