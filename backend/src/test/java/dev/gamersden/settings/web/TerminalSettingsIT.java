package dev.gamersden.settings.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamersden.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static dev.gamersden.settings.web.TerminalSettingsAccessIT.settingsBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code GET/PUT /terminal-settings} — the S13 controls (design.md §6) on the row that belongs to
 * the terminal rather than to the operator.
 *
 * <p>The row is keyed by the access token's {@code terminal} claim, which is the whole reason no
 * request here names a terminal: two machines signed in as the same Admin keep separate settings,
 * and neither can write the other's.
 */
class TerminalSettingsIT extends AbstractApiIntegrationTest {

    private static final String SETTINGS = "/api/v1/terminal-settings";

    @Test
    @DisplayName("a terminal that has never been configured answers with the defaults, and stays unwritten")
    void defaultsForAFreshTerminal() {
        JsonNode settings = get(SETTINGS, adminBearer()).getBody();

        assertThat(settings.get("theme").asText()).isEqualTo("DARK");
        assertThat(settings.get("fontScale").asText()).isEqualTo("DEFAULT");
        assertThat(settings.get("accent").asText()).isEqualTo("#ec3013");
        assertThat(settings.get("loginBgImageId").isNull()).isTrue();
        assertThat(settings.get("sound").asBoolean()).isTrue();
        assertThat(settings.get("autoLockMin").asInt()).isEqualTo(5);
        assertThat(settings.get("receiptCopies").asInt()).isEqualTo(1);

        // A read is a read: the defaults are the column defaults, not a row written on the way past.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM terminal_settings", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("a write round-trips through the database")
    void writeThenRead() {
        Map<String, Object> body = settingsBody(Map.of(
                "theme", "LIGHT",
                "fontScale", "LARGE",
                "accent", "#198038",
                "sound", false,
                "autoLockMin", 10,
                "receiptCopies", 2));

        JsonNode written = put(SETTINGS, body, adminBearer()).getBody();
        JsonNode read = get(SETTINGS, adminBearer()).getBody();

        assertThat(written).isEqualTo(read);
        assertThat(read.get("theme").asText()).isEqualTo("LIGHT");
        assertThat(read.get("fontScale").asText()).isEqualTo("LARGE");
        assertThat(read.get("accent").asText()).isEqualTo("#198038");
        assertThat(read.get("sound").asBoolean()).isFalse();
        assertThat(read.get("autoLockMin").asInt()).isEqualTo(10);
        assertThat(read.get("receiptCopies").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("auto-lock off is 0, and it survives the round trip as 0 rather than as the default")
    void autoLockOff() {
        put(SETTINGS, settingsBody(Map.of("autoLockMin", 0)), adminBearer());

        assertThat(get(SETTINGS, adminBearer()).getBody().get("autoLockMin").asInt()).isZero();
    }

    @Test
    @DisplayName("settings are per terminal — the same Admin on two machines writes two rows")
    void settingsAreKeyedByTheTerminalClaim() {
        HttpHeaders onT2 = bearer(login(adminId, ADMIN_PIN, "T2").getBody().get("accessToken").asText());

        put(SETTINGS, settingsBody(Map.of("theme", "LIGHT", "receiptCopies", 2)), adminBearer());
        put(SETTINGS, settingsBody(Map.of("accent", "#0f62fe")), onT2);

        JsonNode t1 = get(SETTINGS, adminBearer()).getBody();
        JsonNode t2 = get(SETTINGS, onT2).getBody();

        assertThat(t1.get("theme").asText()).isEqualTo("LIGHT");
        assertThat(t1.get("receiptCopies").asInt()).isEqualTo(2);
        assertThat(t2.get("theme").asText()).isEqualTo("DARK");
        assertThat(t2.get("accent").asText()).isEqualTo("#0f62fe");
        assertThat(jdbc.queryForList("SELECT terminal FROM terminal_settings ORDER BY terminal",
                String.class)).containsExactly(TERMINAL, "T2");
    }

    @Test
    @DisplayName("receiptCopies is 1 or 2 — the API refuses anything else")
    void receiptCopiesIsOneOrTwo() {
        for (int copies : new int[] {1, 2}) {
            assertThat(put(SETTINGS, settingsBody(Map.of("receiptCopies", copies)), adminBearer())
                    .getStatusCode().value()).isEqualTo(200);
        }
        for (int copies : new int[] {0, 3, -1}) {
            ResponseEntity<JsonNode> response =
                    put(SETTINGS, settingsBody(Map.of("receiptCopies", copies)), adminBearer());

            assertErrorEnvelope(response, 400, "VALIDATION_FAILED");
            assertThat(response.getBody().get("error").get("details").get("field").asText())
                    .isEqualTo("receiptCopies");
        }
        // The last accepted value is what stands: a refused write is not half-applied.
        assertThat(get(SETTINGS, adminBearer()).getBody().get("receiptCopies").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("receiptCopies is 1 or 2 in the schema too — the column's own CHECK (V001)")
    void receiptCopiesCheckConstraint() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO terminal_settings (terminal, receipt_copies) VALUES ('T9', 3)"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("receipt_copies");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM terminal_settings WHERE terminal = 'T9'",
                Integer.class)).isZero();
    }

    @Test
    @DisplayName("auto-lock is Off / 2 / 5 / 10 and the accent is one of the three swatches")
    void theOtherClosedSetsAreEnforced() {
        assertFieldRejected("autoLockMin", 7);
        assertFieldRejected("autoLockMin", 15);
        assertFieldRejected("accent", "#123456");
        assertFieldRejected("accent", "red");
    }

    @Test
    @DisplayName("an unknown theme or text size is a 400, not a stored surprise")
    void unknownEnumsAreRejected() {
        assertErrorEnvelope(put(SETTINGS, settingsBody(Map.of("theme", "PURPLE")), adminBearer()),
                400, "VALIDATION_FAILED");
        assertErrorEnvelope(put(SETTINGS, settingsBody(Map.of("fontScale", "HUGE")), adminBearer()),
                400, "VALIDATION_FAILED");
    }

    @Test
    @DisplayName("PUT is the whole object — a missing field is refused rather than silently kept")
    void everyFieldIsRequired() {
        Map<String, Object> missingSound = new java.util.HashMap<>(settingsBody(Map.of()));
        missingSound.remove("sound");

        ResponseEntity<JsonNode> response = put(SETTINGS, missingSound, adminBearer());

        assertErrorEnvelope(response, 400, "VALIDATION_FAILED");
        assertThat(response.getBody().get("error").get("details").get("fields").has("sound")).isTrue();
    }

    @Test
    @DisplayName("the accent is stored case-insensitively as the canonical hex")
    void accentIsNormalised() {
        put(SETTINGS, settingsBody(Map.of("accent", "#0F62FE")), adminBearer());

        assertThat(get(SETTINGS, adminBearer()).getBody().get("accent").asText()).isEqualTo("#0f62fe");
    }

    private void assertFieldRejected(String field, Object value) {
        ResponseEntity<JsonNode> response =
                put(SETTINGS, settingsBody(Map.of(field, value)), adminBearer());

        assertErrorEnvelope(response, 400, "VALIDATION_FAILED");
        assertThat(response.getBody().get("error").get("details").get("field").asText())
                .isEqualTo(field);
    }
}
