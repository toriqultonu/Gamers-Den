package dev.gamersden.shift.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamersden.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET/POST /expenses} against a real Postgres.
 *
 * <p>Petty cash has one structural rule and it is the reason this file exists: an expense belongs
 * to the shift that paid it, chosen by the server from the caller's terminal, never by the
 * request. That is what lets the Z subtract exactly the money that left <em>its own</em> drawer
 * (invariant §5.7) — and why money cannot leave a till nobody has opened.
 */
class ExpenseIT extends AbstractApiIntegrationTest {

    private HttpHeaders staff;
    private Long shiftId;

    @BeforeEach
    void openTheTill() {
        staff = adminBearer();
        shiftId = post("/api/v1/shifts", Map.of("openingFloat", 2000), staff)
                .getBody().get("id").asLong();
    }

    @Test
    @DisplayName("an expense is posted to the open shift, by the operator who recorded it")
    void recordingAnExpense() {
        ResponseEntity<JsonNode> recorded = record("Cleaning supplies", "SUPPLIES", 300, false);

        assertThat(recorded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode expense = recorded.getBody();
        assertThat(expense.get("shiftId").asLong()).isEqualTo(shiftId);
        assertThat(expense.get("staffId").asLong()).isEqualTo(adminId);
        assertThat(expense.get("category").asText()).isEqualTo("SUPPLIES");
        assertThat(expense.get("amount").asInt()).isEqualTo(300);
        assertThat(expense.get("createdAt").asText()).isNotBlank();
        // No voucher was asked for, so no job and no field on the wire.
        assertThat(expense.has("printJobId")).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM print_jobs", Integer.class)).isZero();
    }

    @Test
    @DisplayName("voucher=true renders and queues the P4 slip in the same transaction")
    void recordingAnExpenseWithAVoucher() {
        JsonNode expense = record("Bus fare", "OTHER", 120, true).getBody();

        long printJobId = expense.get("printJobId").asLong();
        Map<String, Object> job = jdbc.queryForMap("SELECT type, ref_id, status, device_id, "
                + "operator_id, rendered_text FROM print_jobs WHERE id = ?", printJobId);
        assertThat(job).containsEntry("type", "EXPENSE_VOUCHER")
                .containsEntry("ref_id", expense.get("id").asLong())
                .containsEntry("status", "QUEUED")
                .containsEntry("device_id", TERMINAL)
                .containsEntry("operator_id", adminId);
        assertThat((String) job.get("rendered_text"))
                .contains("EXPENSE VOUCHER").contains("Bus fare").contains("SIGNATURE");
    }

    @Test
    @DisplayName("the list is this shift's petty cash, newest first")
    void listingAShiftsPettyCash() {
        record("Cleaning supplies", "SUPPLIES", 300, false);
        record("Bus fare", "OTHER", 120, false);

        JsonNode listed = get("/api/v1/expenses", staff).getBody();

        assertThat(listed).hasSize(2);
        assertThat(listed.get(0).get("description").asText()).isEqualTo("Bus fare");
        assertThat(listed.get(1).get("description").asText()).isEqualTo("Cleaning supplies");
    }

    @Test
    @DisplayName("a closed shift's petty cash can still be read back by id")
    void listingAClosedShift() {
        record("Cleaning supplies", "SUPPLIES", 300, false);
        post("/api/v1/shifts/current/close", Map.of("countedCash", 1700), staff);

        JsonNode listed = get("/api/v1/expenses?shiftId=" + shiftId, staff).getBody();

        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).get("amount").asInt()).isEqualTo(300);
    }

    @Test
    @DisplayName("with no shift open there is no drawer to take the money out of")
    void noShiftOpen() {
        post("/api/v1/shifts/current/close", Map.of("countedCash", 2000), staff);

        assertErrorEnvelope(record("Cleaning supplies", "SUPPLIES", 300, false), 409, "CONFLICT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM expenses", Integer.class)).isZero();
    }

    @Test
    @DisplayName("an expense has to be a real amount with a description and a known category")
    void validation() {
        assertThat(record("Cleaning supplies", "SUPPLIES", 0, false).getStatusCode().value())
                .isEqualTo(400);
        assertThat(record("", "SUPPLIES", 300, false).getStatusCode().value()).isEqualTo(400);
        assertThat(record("Cleaning supplies", "SNACKS", 300, false).getStatusCode().value())
                .isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM expenses", Integer.class)).isZero();
    }

    @Test
    @DisplayName("recording petty cash needs a signed-in operator")
    void anonymousIsRejected() {
        assertThat(post("/api/v1/expenses",
                Map.of("description", "Cleaning supplies", "category", "SUPPLIES", "amount", 300),
                null).getStatusCode().value()).isEqualTo(401);
    }

    private ResponseEntity<JsonNode> record(String description, String category, int amount,
                                            boolean voucher) {
        return post("/api/v1/expenses?voucher=" + voucher,
                Map.of("description", description, "category", category, "amount", amount), staff);
    }
}
