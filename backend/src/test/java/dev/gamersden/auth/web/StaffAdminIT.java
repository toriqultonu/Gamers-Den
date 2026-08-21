package dev.gamersden.auth.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamersden.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code /staff} — Admin-only CRUD and the role guard the permission matrix promises. */
class StaffAdminIT extends AbstractApiIntegrationTest {

    private static final String CASHIER_PIN = "4321";

    @Test
    void adminCreatesACashierWhoCanThenSignIn() {
        ResponseEntity<JsonNode> created = post("/api/v1/staff",
                Map.of("name", "Rafi", "role", "CASHIER", "pin", CASHIER_PIN), adminBearer());

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("role").asText()).isEqualTo("CASHIER");
        assertThat(created.getBody().get("active").asBoolean()).isTrue();
        assertThat(created.getBody().has("pin")).isFalse();

        Long id = created.getBody().get("id").asLong();
        assertThat(login(id, CASHIER_PIN).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aDuplicateNameIs409DuplicateName() {
        post("/api/v1/staff", Map.of("name", "Rafi", "role", "CASHIER", "pin", CASHIER_PIN), adminBearer());

        ResponseEntity<JsonNode> again = post("/api/v1/staff",
                Map.of("name", "Rafi", "role", "MANAGER", "pin", "1111"), adminBearer());

        assertErrorEnvelope(again, 409, "DUPLICATE_NAME");
    }

    @Test
    void adminIsNotHandedOutThroughTheApi() {
        ResponseEntity<JsonNode> response = post("/api/v1/staff",
                Map.of("name", "Shadow", "role", "ADMIN", "pin", "1111"), adminBearer());

        assertErrorEnvelope(response, 400, "VALIDATION_FAILED");
    }

    @Test
    void listReturnsTheSeededAdminAndEveryHire() {
        post("/api/v1/staff", Map.of("name", "Rafi", "role", "CASHIER", "pin", CASHIER_PIN), adminBearer());

        ResponseEntity<JsonNode> list = get("/api/v1/staff", adminBearer());

        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody()).hasSize(2);
    }

    @Test
    void patchRenamesAndRepinsAndTheOldPinStopsWorking() {
        Long id = createCashier("Rafi");

        ResponseEntity<JsonNode> patched = patch("/api/v1/staff/" + id,
                Map.of("name", "Rafiul", "pin", "5555"), adminBearer());

        assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patched.getBody().get("name").asText()).isEqualTo("Rafiul");
        assertErrorEnvelope(login(id, CASHIER_PIN), 401, "UNAUTHORIZED");
        assertThat(login(id, "5555").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void patchToATakenNameIs409DuplicateName() {
        createCashier("Rafi");
        Long other = createCashier("Nabil");

        assertErrorEnvelope(patch("/api/v1/staff/" + other, Map.of("name", "Rafi"), adminBearer()),
                409, "DUPLICATE_NAME");
    }

    @Test
    void deleteDeactivatesAndCutsTheAccountsSessions() {
        Long id = createCashier("Rafi");
        String refreshCookie = refreshCookieOf(login(id, CASHIER_PIN)).orElseThrow();

        assertThat(delete("/api/v1/staff/" + id, adminBearer()).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        // The row survives so shifts and transactions keep their reference; the account does not.
        assertThat(jdbc.queryForObject("SELECT active FROM staff WHERE id = ?", Boolean.class, id))
                .isFalse();
        assertErrorEnvelope(login(id, CASHIER_PIN), 401, "UNAUTHORIZED");
        assertErrorEnvelope(post("/api/v1/auth/refresh", null, cookie(refreshCookie)),
                401, "UNAUTHORIZED");
    }

    @Test
    void deletingSomeoneOnAnOpenShiftIs409StaffOnShift() {
        Long id = createCashier("Rafi");
        jdbc.update("INSERT INTO shifts (staff_id, terminal, opening_float) VALUES (?, 'T3', 2000)", id);

        assertErrorEnvelope(delete("/api/v1/staff/" + id, adminBearer()), 409, "STAFF_ON_SHIFT");
        assertThat(jdbc.queryForObject("SELECT active FROM staff WHERE id = ?", Boolean.class, id))
                .isTrue();
    }

    @Test
    void deletingAnUnknownStaffIs404() {
        assertErrorEnvelope(delete("/api/v1/staff/999999", adminBearer()), 404, "NOT_FOUND");
    }

    @Test
    void aCashierOnAnAdminEndpointGetsThe403Envelope() {
        Long id = createCashier("Rafi");
        HttpHeaders cashier = bearerFor(id, CASHIER_PIN);

        assertErrorEnvelope(get("/api/v1/staff", cashier), 403, "FORBIDDEN");
        assertErrorEnvelope(post("/api/v1/staff",
                Map.of("name", "Nabil", "role", "CASHIER", "pin", "1111"), cashier), 403, "FORBIDDEN");
        assertErrorEnvelope(patch("/api/v1/staff/" + id, Map.of("name", "Nabil"), cashier),
                403, "FORBIDDEN");
        assertErrorEnvelope(delete("/api/v1/staff/" + id, cashier), 403, "FORBIDDEN");
    }

    @Test
    void aManagerIsAlsoBelowTheAdminBar() {
        ResponseEntity<JsonNode> created = post("/api/v1/staff",
                Map.of("name", "Tanvir", "role", "MANAGER", "pin", "2222"), adminBearer());
        HttpHeaders manager = bearerFor(created.getBody().get("id").asLong(), "2222");

        assertErrorEnvelope(get("/api/v1/staff", manager), 403, "FORBIDDEN");
    }

    @Test
    void noTokenAtAllIs401NotForbidden() {
        assertErrorEnvelope(get("/api/v1/staff", null), 401, "UNAUTHORIZED");
    }

    @Test
    void aGarbageBearerTokenIs401() {
        assertErrorEnvelope(get("/api/v1/staff", bearer("not.a.jwt")), 401, "UNAUTHORIZED");
    }

    private Long createCashier(String name) {
        ResponseEntity<JsonNode> created = post("/api/v1/staff",
                Map.of("name", name, "role", "CASHIER", "pin", CASHIER_PIN), adminBearer());
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody().get("id").asLong();
    }
}
