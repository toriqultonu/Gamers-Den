package dev.gamersden.auth.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamersden.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code GET/PUT /me/prefs} — any role, and only ever the caller's own row (design.md S13). */
class MePrefsIT extends AbstractApiIntegrationTest {

    @Test
    void readsTheSeededSwatch() {
        ResponseEntity<JsonNode> response = get("/api/v1/me/prefs", adminBearer());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("avatarColor").asText()).isEqualTo("#ec3013");
    }

    @Test
    void writesAndReadsBackANewSwatch() {
        ResponseEntity<JsonNode> written =
                put("/api/v1/me/prefs", Map.of("avatarColor", "#4f8ef7"), adminBearer());

        assertThat(written.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(written.getBody().get("avatarColor").asText()).isEqualTo("#4f8ef7");
        assertThat(get("/api/v1/me/prefs", adminBearer()).getBody().get("avatarColor").asText())
                .isEqualTo("#4f8ef7");
    }

    @Test
    void aNullColourResetsToTheDefault() {
        put("/api/v1/me/prefs", Map.of("avatarColor", "#4f8ef7"), adminBearer());

        ResponseEntity<JsonNode> reset = put("/api/v1/me/prefs",
                Collections.singletonMap("avatarColor", null), adminBearer());

        assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reset.getBody().get("avatarColor").isNull()).isTrue();
    }

    @Test
    void aNonHexColourIsRejected() {
        assertErrorEnvelope(put("/api/v1/me/prefs", Map.of("avatarColor", "red"), adminBearer()),
                400, "VALIDATION_FAILED");
    }

    @Test
    void aCashierOwnsTheirOwnPrefs() {
        Long id = post("/api/v1/staff",
                Map.of("name", "Rafi", "role", "CASHIER", "pin", "4321"), adminBearer())
                .getBody().get("id").asLong();
        HttpHeaders cashier = bearerFor(id, "4321");

        assertThat(put("/api/v1/me/prefs", Map.of("avatarColor", "#22c55e"), cashier)
                .getBody().get("avatarColor").asText()).isEqualTo("#22c55e");
        // ...and only their own: the seeded Admin swatch is untouched.
        assertThat(get("/api/v1/me/prefs", adminBearer()).getBody().get("avatarColor").asText())
                .isEqualTo("#ec3013");
    }

    @Test
    void prefsNeedATokenLikeEverythingElse() {
        assertErrorEnvelope(get("/api/v1/me/prefs", null), 401, "UNAUTHORIZED");
    }
}
