package dev.gamersden.settings.web;

import dev.gamersden.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The permission matrix for S13 (api-contract.md §1: "Stations CRUD, pricing, staff CRUD, terminal
 * settings write — Admin only"; the read is not on that row).
 *
 * <p>The split matters more here than the screen suggests. The read is open to all three roles
 * because the theme, the text size and the accent decide how the app paints itself for whoever is
 * on shift — a cashier who cannot read the settings cannot render the venue's own terminal. The
 * write is Admin's alone, and the API is where that is kept: S13 greying out its controls for a
 * cashier is cosmetic.
 */
class TerminalSettingsAccessIT extends AbstractApiIntegrationTest {

    private static final String MANAGER_PIN = "1111";
    private static final String CASHIER_PIN = "4321";

    private HttpHeaders manager;
    private HttpHeaders cashier;

    @BeforeEach
    void twoMoreOperators() {
        manager = bearerFor(createStaff("Nusrat", "MANAGER", MANAGER_PIN), MANAGER_PIN);
        cashier = bearerFor(createStaff("Rafi", "CASHIER", CASHIER_PIN), CASHIER_PIN);
    }

    @Test
    @DisplayName("every role reads the settings")
    void anyRoleReads() {
        for (HttpHeaders operator : new HttpHeaders[] {adminBearer(), manager, cashier}) {
            var response = get("/api/v1/terminal-settings", operator);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody().get("theme").asText()).isEqualTo("DARK");
        }
    }

    @Test
    @DisplayName("a manager and a cashier are refused the write with the error envelope")
    void nonAdminWriteIsForbidden() {
        assertErrorEnvelope(put("/api/v1/terminal-settings", settingsBody(), manager), 403, "FORBIDDEN");
        assertErrorEnvelope(put("/api/v1/terminal-settings", settingsBody(), cashier), 403, "FORBIDDEN");
    }

    @Test
    @DisplayName("a manager and a cashier are refused the background upload too")
    void nonAdminUploadIsForbidden() {
        assertErrorEnvelope(LoginBackgroundIT.upload(rest, LoginBackgroundIT.png(), "bg.png",
                "image/png", manager), 403, "FORBIDDEN");
        assertErrorEnvelope(LoginBackgroundIT.upload(rest, LoginBackgroundIT.png(), "bg.png",
                "image/png", cashier), 403, "FORBIDDEN");
    }

    @Test
    @DisplayName("a refused write changes nothing")
    void aRefusedWriteIsNotHalfApplied() {
        put("/api/v1/terminal-settings", settingsBody(), cashier);

        assertThat(get("/api/v1/terminal-settings", cashier).getBody().get("theme").asText())
                .isEqualTo("DARK");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM terminal_settings", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("an admin writes")
    void adminWrites() {
        var response = put("/api/v1/terminal-settings", settingsBody(), adminBearer());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("theme").asText()).isEqualTo("LIGHT");
    }

    @Test
    @DisplayName("no token at all is a 401, not a 403 — the settings are not public, only the image is")
    void anonymousIsUnauthorised() {
        assertErrorEnvelope(get("/api/v1/terminal-settings", null), 401, "UNAUTHORIZED");
        assertErrorEnvelope(put("/api/v1/terminal-settings", settingsBody(), null), 401, "UNAUTHORIZED");
    }

    static Map<String, Object> settingsBody() {
        return settingsBody(Map.of("theme", "LIGHT"));
    }

    /** The full object PUT expects, with {@code overrides} replacing whatever it names. */
    static Map<String, Object> settingsBody(Map<String, Object> overrides) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of(
                "theme", "DARK",
                "fontScale", "DEFAULT",
                "accent", "#ec3013",
                "sound", true,
                "autoLockMin", 5,
                "receiptCopies", 1));
        body.put("loginBgImageId", null);
        body.putAll(overrides);
        return body;
    }

    private Long createStaff(String name, String role, String pin) {
        return post("/api/v1/staff", Map.of("name", name, "role", role, "pin", pin), adminBearer())
                .getBody().get("id").asLong();
    }
}
