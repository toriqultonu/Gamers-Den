package dev.gamersden.support;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP-level base for the auth suite: a real filter chain, a real Postgres, no mocks. Every test
 * starts from the V001 seed — one Admin with PIN 1234, no extra staff, no shifts, no live
 * tokens, an empty floor, an empty menu, an empty member directory and the seeded rate card.
 */
public abstract class AbstractApiIntegrationTest extends AbstractIntegrationTest {

    protected static final String ADMIN_PIN = "1234";
    protected static final String TERMINAL = "T1";
    protected static final String REFRESH_COOKIE = "gd_refresh";

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected PasswordEncoder pins;

    protected Long adminId;

    @BeforeEach
    void resetAuthState() {
        jdbc.update("DELETE FROM refresh_tokens");
        jdbc.update("DELETE FROM idempotency_keys");
        // Money next, because a transaction references the session, cart, member, shift and staff
        // it belongs to, and a print job references its operator (B10).
        jdbc.update("DELETE FROM payment_splits");
        jdbc.update("DELETE FROM print_jobs");
        jdbc.update("DELETE FROM transactions");
        // Then floor state, deepest reference last: cart lines -> carts -> sessions -> stations
        // -> shifts. Carts point at sessions, sessions at both stations and shifts.
        jdbc.update("DELETE FROM cart_lines");
        jdbc.update("DELETE FROM carts");
        jdbc.update("DELETE FROM stock_movements");
        jdbc.update("DELETE FROM items");
        jdbc.update("DELETE FROM session_blocks");
        jdbc.update("DELETE FROM sessions");
        // Members go after sessions, which reference them, and after their own two ledgers.
        jdbc.update("DELETE FROM wallet_ledger");
        jdbc.update("DELETE FROM points_ledger");
        jdbc.update("DELETE FROM members");
        jdbc.update("DELETE FROM stations");
        // Petty cash points at both the shift that paid it and the staff who recorded it (B11).
        jdbc.update("DELETE FROM expenses");
        jdbc.update("DELETE FROM shifts");
        jdbc.update("DELETE FROM alerts");
        jdbc.update("DELETE FROM staff WHERE name <> 'Admin'");
        jdbc.update("UPDATE staff SET failed_pins = 0, locked_until = NULL, active = TRUE, "
                + "avatar_color = '#ec3013', pin_hash = ? WHERE name = 'Admin'", pins.encode(ADMIN_PIN));
        adminId = jdbc.queryForObject("SELECT id FROM staff WHERE name = 'Admin'", Long.class);
        resetSeededPricing();
    }

    /** Back to the V001 rate card: PS5 120/80, PS4 80/50, morning -25% from 10:00 to 14:00. */
    private void resetSeededPricing() {
        jdbc.update("UPDATE pricing SET per_hour = 120, per_half_hour = 80, "
                + "morning_discount_pct = 25, morning_start = '10:00', morning_end = '14:00', "
                + "updated_at = now() "
                + "WHERE console_type = 'PS5'");
        jdbc.update("UPDATE pricing SET per_hour = 80, per_half_hour = 50, "
                + "morning_discount_pct = 25, morning_start = '10:00', morning_end = '14:00', "
                + "updated_at = now() "
                + "WHERE console_type = 'PS4'");
    }

    // ---- requests -------------------------------------------------------------------------

    protected ResponseEntity<JsonNode> post(String path, Object body, HttpHeaders headers) {
        return rest.exchange(path, HttpMethod.POST, entity(body, headers), JsonNode.class);
    }

    protected ResponseEntity<JsonNode> get(String path, HttpHeaders headers) {
        return rest.exchange(path, HttpMethod.GET, entity(null, headers), JsonNode.class);
    }

    protected ResponseEntity<JsonNode> put(String path, Object body, HttpHeaders headers) {
        return rest.exchange(path, HttpMethod.PUT, entity(body, headers), JsonNode.class);
    }

    protected ResponseEntity<JsonNode> patch(String path, Object body, HttpHeaders headers) {
        return rest.exchange(path, HttpMethod.PATCH, entity(body, headers), JsonNode.class);
    }

    protected ResponseEntity<JsonNode> delete(String path, HttpHeaders headers) {
        return rest.exchange(path, HttpMethod.DELETE, entity(null, headers), JsonNode.class);
    }

    private static HttpEntity<Object> entity(Object body, HttpHeaders headers) {
        HttpHeaders copy = new HttpHeaders();
        if (headers != null) {
            copy.addAll(headers);
        }
        copy.setContentType(MediaType.APPLICATION_JSON);
        copy.setAccept(List.of(MediaType.APPLICATION_JSON));
        return new HttpEntity<>(body, copy);
    }

    // ---- auth helpers ---------------------------------------------------------------------

    protected ResponseEntity<JsonNode> login(Long staffId, String pin) {
        return login(staffId, pin, TERMINAL);
    }

    protected ResponseEntity<JsonNode> login(Long staffId, String pin, String terminal) {
        return post("/api/v1/auth/login",
                java.util.Map.of("staffId", staffId, "pin", pin, "terminal", terminal), null);
    }

    /** Signs in and returns a ready-to-use {@code Authorization: Bearer} header set. */
    protected HttpHeaders bearerFor(Long staffId, String pin) {
        ResponseEntity<JsonNode> response = login(staffId, pin);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return bearer(response.getBody().get("accessToken").asText());
    }

    protected HttpHeaders adminBearer() {
        return bearerFor(adminId, ADMIN_PIN);
    }

    protected static HttpHeaders bearer(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return headers;
    }

    protected static HttpHeaders cookie(String refreshToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, REFRESH_COOKIE + "=" + refreshToken);
        return headers;
    }

    /** The refresh token value out of {@code Set-Cookie}; empty once the cookie is cleared. */
    protected static Optional<String> refreshCookieOf(ResponseEntity<?> response) {
        List<String> setCookie = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        if (setCookie == null) {
            return Optional.empty();
        }
        return setCookie.stream()
                .filter(header -> header.startsWith(REFRESH_COOKIE + "="))
                .map(header -> header.substring((REFRESH_COOKIE + "=").length()).split(";", 2)[0])
                .filter(value -> !value.isBlank())
                .findFirst();
    }

    protected static String errorCode(ResponseEntity<JsonNode> response) {
        return response.getBody().get("error").get("code").asText();
    }

    /** Every non-2xx body is the envelope, traceId included (ARCHITECTURE.md §4.4). */
    protected static void assertErrorEnvelope(ResponseEntity<JsonNode> response, int status, String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        JsonNode error = response.getBody().get("error");
        assertThat(error).as("error envelope in %s", response.getBody()).isNotNull();
        assertThat(error.get("code").asText()).isEqualTo(code);
        assertThat(error.get("message").asText()).isNotBlank();
        assertThat(error.get("traceId").asText()).isNotBlank();
    }
}
