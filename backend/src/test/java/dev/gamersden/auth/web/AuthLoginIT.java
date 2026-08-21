package dev.gamersden.auth.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamersden.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code POST /auth/login} — the happy path, the wrong-PIN counter and the 15-minute lock. */
class AuthLoginIT extends AbstractApiIntegrationTest {

    @Test
    void happyLoginReturnsAnAccessTokenARefreshCookieAndTheStaffRow() {
        ResponseEntity<JsonNode> response = login(adminId, ADMIN_PIN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body.get("accessToken").asText()).isNotBlank();
        assertThat(body.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(body.get("expiresIn").asLong()).isEqualTo(900);      // 15 min, api-contract §1
        assertThat(body.get("terminal").asText()).isEqualTo(TERMINAL);
        assertThat(body.has("shiftId")).isFalse();                       // no shift open yet
        assertThat(body.get("staff").get("name").asText()).isEqualTo("Admin");
        assertThat(body.get("staff").get("role").asText()).isEqualTo("ADMIN");
        assertThat(body.get("staff").has("pinHash")).isFalse();

        assertThat(refreshCookieOf(response)).isPresent();
        String setCookie = response.getHeaders().getFirst("Set-Cookie");
        assertThat(setCookie).contains("HttpOnly").contains("SameSite=Strict")
                .contains("Path=/api/v1/auth").contains("Max-Age=43200");   // 12 h
    }

    @Test
    void theAccessTokenOpensAGuardedRoute() {
        ResponseEntity<JsonNode> prefs = get("/api/v1/me/prefs", adminBearer());

        assertThat(prefs.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(prefs.getBody().get("avatarColor").asText()).isEqualTo("#ec3013");
    }

    @Test
    void theShiftClaimCarriesTheTerminalsOpenShift() {
        jdbc.update("INSERT INTO shifts (staff_id, terminal, opening_float) VALUES (?, ?, 0)",
                adminId, TERMINAL);
        Long shiftId = jdbc.queryForObject(
                "SELECT id FROM shifts WHERE terminal = ? AND closed_at IS NULL", Long.class, TERMINAL);

        ResponseEntity<JsonNode> response = login(adminId, ADMIN_PIN);

        assertThat(response.getBody().get("shiftId").asLong()).isEqualTo(shiftId);
    }

    @Test
    void wrongPinIs401AndCountsDownTheRemainingAttempts() {
        ResponseEntity<JsonNode> response = login(adminId, "9999");

        assertErrorEnvelope(response, 401, "UNAUTHORIZED");
        assertThat(response.getBody().get("error").get("details").get("attemptsRemaining").asInt())
                .isEqualTo(4);
        assertThat(refreshCookieOf(response)).isEmpty();
        assertThat(failedPins()).isEqualTo(1);
    }

    @Test
    void fiveWrongPinsLockTheAccountFor15Minutes() {
        for (int attempt = 1; attempt <= 4; attempt++) {
            assertErrorEnvelope(login(adminId, "9999"), 401, "UNAUTHORIZED");
        }

        ResponseEntity<JsonNode> fifth = login(adminId, "9999");

        assertErrorEnvelope(fifth, 423, "LOCKED_PIN");
        JsonNode details = fifth.getBody().get("error").get("details");
        assertThat(details.get("retryAfterSeconds").asLong())
                .isBetween(14L * 60, 15L * 60);
        assertThat(failedPins()).isEqualTo(5);
        assertThat(lockedUntil()).isAfter(OffsetDateTime.now().plusMinutes(14));
    }

    @Test
    void theRightPinIsStillRefusedWhileTheLockHolds() {
        for (int attempt = 1; attempt <= 5; attempt++) {
            login(adminId, "9999");
        }

        assertErrorEnvelope(login(adminId, ADMIN_PIN), 423, "LOCKED_PIN");
    }

    @Test
    void theLockClearsWhenItExpiresAndTheCounterStartsOver() {
        for (int attempt = 1; attempt <= 5; attempt++) {
            login(adminId, "9999");
        }
        // Serve the sentence: the lock is a stored timestamp, so winding it back is the whole test.
        jdbc.update("UPDATE staff SET locked_until = now() - interval '1 second' WHERE id = ?", adminId);

        assertThat(login(adminId, ADMIN_PIN).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(failedPins()).isZero();
        assertThat(lockedUntil()).isNull();
    }

    @Test
    void aSuccessfulLoginClearsAPartialFailureStreak() {
        login(adminId, "9999");
        login(adminId, "9999");

        assertThat(login(adminId, ADMIN_PIN).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(failedPins()).isZero();
    }

    @Test
    void anUnknownStaffIdIsIndistinguishableFromAWrongPin() {
        assertErrorEnvelope(login(999_999L, ADMIN_PIN), 401, "UNAUTHORIZED");
    }

    @Test
    void aDeactivatedAccountCannotSignIn() {
        jdbc.update("UPDATE staff SET active = FALSE WHERE id = ?", adminId);

        assertErrorEnvelope(login(adminId, ADMIN_PIN), 401, "UNAUTHORIZED");
    }

    @Test
    void aMalformedBodyIsRejectedBeforeAnyPinCheck() {
        ResponseEntity<JsonNode> response = post("/api/v1/auth/login",
                Map.of("staffId", adminId, "pin", "12", "terminal", TERMINAL), null);

        assertErrorEnvelope(response, 400, "VALIDATION_FAILED");
        assertThat(failedPins()).isZero();
    }

    private int failedPins() {
        return jdbc.queryForObject("SELECT failed_pins FROM staff WHERE id = ?", Integer.class, adminId);
    }

    private OffsetDateTime lockedUntil() {
        return jdbc.queryForObject("SELECT locked_until FROM staff WHERE id = ?",
                OffsetDateTime.class, adminId);
    }
}
