package dev.gamersden.auth.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamersden.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code POST /auth/refresh|logout} — rotation, reuse detection and revocation. */
class AuthRefreshIT extends AbstractApiIntegrationTest {

    @Test
    void refreshRotatesTheCookieAndMintsAFreshAccessToken() {
        ResponseEntity<JsonNode> login = login(adminId, ADMIN_PIN);
        String first = refreshCookieOf(login).orElseThrow();

        ResponseEntity<JsonNode> refreshed = post("/api/v1/auth/refresh", null, cookie(first));

        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        String second = refreshCookieOf(refreshed).orElseThrow();
        assertThat(second).isNotEqualTo(first);
        assertThat(refreshed.getBody().get("accessToken").asText()).isNotBlank();
        assertThat(refreshed.getBody().get("staff").get("id").asLong()).isEqualTo(adminId);

        // The old row is spent and linked to its successor; the new one is live.
        assertThat(liveTokenCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NOT NULL AND rotated_to IS NOT NULL",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void theRotatedAccessTokenOpensAGuardedRoute() {
        String cookie = refreshCookieOf(login(adminId, ADMIN_PIN)).orElseThrow();
        ResponseEntity<JsonNode> refreshed = post("/api/v1/auth/refresh", null, cookie(cookie));

        ResponseEntity<JsonNode> prefs = get("/api/v1/me/prefs",
                bearer(refreshed.getBody().get("accessToken").asText()));

        assertThat(prefs.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aSpentCookieIsRefusedAndBurnsTheWholeSessionFamily() {
        String first = refreshCookieOf(login(adminId, ADMIN_PIN)).orElseThrow();
        post("/api/v1/auth/refresh", null, cookie(first));

        ResponseEntity<JsonNode> replay = post("/api/v1/auth/refresh", null, cookie(first));

        assertErrorEnvelope(replay, 401, "UNAUTHORIZED");
        // Reuse reads as theft: the successor is revoked too, so a PIN is needed to get back in.
        assertThat(liveTokenCount()).isZero();
    }

    @Test
    void refreshWithoutACookieIs401() {
        assertErrorEnvelope(post("/api/v1/auth/refresh", null, null), 401, "UNAUTHORIZED");
    }

    @Test
    void refreshWithAnUnknownCookieIs401() {
        assertErrorEnvelope(post("/api/v1/auth/refresh", null, cookie("not-a-real-token")),
                401, "UNAUTHORIZED");
    }

    @Test
    void anExpiredCookieIs401() {
        String cookie = refreshCookieOf(login(adminId, ADMIN_PIN)).orElseThrow();
        jdbc.update("UPDATE refresh_tokens SET expires_at = now() - interval '1 second'");

        assertErrorEnvelope(post("/api/v1/auth/refresh", null, cookie(cookie)), 401, "UNAUTHORIZED");
    }

    @Test
    void logoutRevokesTheRefreshTokenAndClearsTheCookie() {
        String cookie = refreshCookieOf(login(adminId, ADMIN_PIN)).orElseThrow();

        ResponseEntity<JsonNode> logout = post("/api/v1/auth/logout", null, cookie(cookie));

        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(refreshCookieOf(logout)).isEmpty();
        assertThat(logout.getHeaders().getFirst("Set-Cookie")).contains("Max-Age=0");
        assertThat(liveTokenCount()).isZero();

        assertErrorEnvelope(post("/api/v1/auth/refresh", null, cookie(cookie)), 401, "UNAUTHORIZED");
    }

    @Test
    void logoutWithoutACookieStillSucceeds() {
        assertThat(post("/api/v1/auth/logout", null, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void logoutLeavesOtherTerminalsSignedIn() {
        String t1 = refreshCookieOf(login(adminId, ADMIN_PIN, "T1")).orElseThrow();
        String t2 = refreshCookieOf(login(adminId, ADMIN_PIN, "T2")).orElseThrow();

        post("/api/v1/auth/logout", null, cookie(t1));

        assertThat(post("/api/v1/auth/refresh", null, cookie(t2)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    private int liveTokenCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NULL", Integer.class);
    }
}
