package dev.gamersden.booking.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamersden.support.BookingFixtures;
import dev.gamersden.support.AbstractApiIntegrationTest;
import dev.gamersden.support.FloorFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET/PUT /booking-settings} (docs/bookings.md §1).
 *
 * <p>Two things are proved here. The permission split is real — every operator reads the settings
 * because the booking form prices against them, and only an Admin writes them (api-contract.md §1's
 * matrix); a Cashier gets 403 from the API, not from a hidden button. And an edit reaches
 * <strong>new bookings only</strong>: the fee and the cutoff a customer already paid under live on
 * their booking row as snapshots and nothing here can move them (invariant §5.11).
 */
class BookingSettingsIT extends AbstractApiIntegrationTest {

    private static final int PS5_HALF_HOUR = 80;

    private BookingFixtures fixtures;
    private HttpHeaders admin;
    private Long stationId;

    @BeforeEach
    void seed() {
        fixtures = new BookingFixtures(jdbc);
        admin = adminBearer();
        stationId = createStation();
        new FloorFixtures(jdbc).openShift(adminId, TERMINAL);
    }

    @Test
    @DisplayName("the seeded row is the documented default: on, 100 BDT, 2 hours")
    void defaults() {
        JsonNode settings = get("/api/v1/booking-settings", admin).getBody();

        assertThat(settings.get("enabled").asBoolean()).isTrue();
        assertThat(settings.get("packageFee").asInt()).isEqualTo(100);
        assertThat(settings.get("cancelCutoffHours").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("an Admin sets them and the row records who did it")
    void adminWrites() {
        ResponseEntity<JsonNode> updated = put("/api/v1/booking-settings",
                Map.of("enabled", false, "packageFee", 150, "cancelCutoffHours", 6), admin);

        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody().get("enabled").asBoolean()).isFalse();
        assertThat(updated.getBody().get("packageFee").asInt()).isEqualTo(150);
        assertThat(updated.getBody().get("cancelCutoffHours").asInt()).isEqualTo(6);
        assertThat(fixtures.settings()).containsEntry("enabled", false)
                .containsEntry("package_fee", 150)
                .containsEntry("cancel_cutoff_hours", 6)
                .containsEntry("updated_by", adminId);
    }

    @Test
    @DisplayName("a write moves the audit stamp — S10 shows when it last changed")
    void writeStampsTheRow() {
        OffsetDateTime before = OffsetDateTime.parse(
                get("/api/v1/booking-settings", admin).getBody().get("updatedAt").asText());

        OffsetDateTime after = OffsetDateTime.parse(put("/api/v1/booking-settings",
                Map.of("packageFee", 175), admin).getBody().get("updatedAt").asText());

        assertThat(after).isAfter(before);
    }

    @Test
    @DisplayName("an omitted field keeps its stored value")
    void partialWrite() {
        put("/api/v1/booking-settings", Map.of("packageFee", 250), admin);

        JsonNode settings = get("/api/v1/booking-settings", admin).getBody();
        assertThat(settings.get("packageFee").asInt()).isEqualTo(250);
        assertThat(settings.get("enabled").asBoolean()).isTrue();
        assertThat(settings.get("cancelCutoffHours").asInt()).isEqualTo(2);
    }

    // ---- the permission split ------------------------------------------------------------------

    @Test
    @DisplayName("a cashier reads the settings but cannot write them — 403 from the API")
    void cashierMayNotWrite() {
        HttpHeaders cashier = cashierBearer();

        assertThat(get("/api/v1/booking-settings", cashier).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> refused = put("/api/v1/booking-settings",
                Map.of("packageFee", 0), cashier);

        assertErrorEnvelope(refused, 403, "FORBIDDEN");
        assertThat(fixtures.settings()).containsEntry("package_fee", 100);
    }

    @Test
    @DisplayName("a manager cannot write them either — this row is Admin alone")
    void managerMayNotWrite() {
        Long managerId = post("/api/v1/staff",
                Map.of("name", "Nusrat", "role", "MANAGER", "pin", "5678"), admin)
                .getBody().get("id").asLong();

        assertErrorEnvelope(put("/api/v1/booking-settings", Map.of("enabled", false),
                bearerFor(managerId, "5678")), 403, "FORBIDDEN");
        assertThat(fixtures.settings()).containsEntry("enabled", true);
    }

    @Test
    @DisplayName("negative money and negative hours are refused")
    void validation() {
        assertErrorEnvelope(put("/api/v1/booking-settings", Map.of("packageFee", -1), admin),
                400, "VALIDATION_FAILED");
        assertErrorEnvelope(put("/api/v1/booking-settings", Map.of("cancelCutoffHours", -3), admin),
                400, "VALIDATION_FAILED");
        assertThat(fixtures.settings()).containsEntry("package_fee", 100)
                .containsEntry("cancel_cutoff_hours", 2);
    }

    // ---- new bookings only ------------------------------------------------------------------------

    @Test
    @DisplayName("an edit moves the next booking, never the one already sold")
    void snapshotsSurviveAnEdit() {
        long soldUnderTheOldTerms = create(2, PS5_HALF_HOUR * 2 + 100);
        assertThat(fixtures.booking(soldUnderTheOldTerms))
                .containsEntry("package_fee", 100)
                .containsEntry("cutoff_hours", 2);

        put("/api/v1/booking-settings", Map.of("packageFee", 400, "cancelCutoffHours", 12), admin);
        long soldUnderTheNewTerms = create(2, PS5_HALF_HOUR * 2 + 400);

        assertThat(fixtures.booking(soldUnderTheOldTerms))
                .containsEntry("package_fee", 100)
                .containsEntry("cutoff_hours", 2);
        assertThat(fixtures.booking(soldUnderTheNewTerms))
                .containsEntry("package_fee", 400)
                .containsEntry("cutoff_hours", 12);
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private long create(int blocks, int amount) {
        HttpHeaders keyed = new HttpHeaders();
        keyed.addAll(admin);
        keyed.add("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<JsonNode> created = post("/api/v1/bookings", Map.of(
                "stationId", stationId,
                "name", "Rifat Hasan",
                "startAt", tomorrowAtSix().toString(),
                "blocks", blocks,
                "method", "CASH"), keyed);
        assertThat(created.getStatusCode()).as("create failed: %s", created.getBody())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("booking").get("total").asInt()).isEqualTo(amount);
        return created.getBody().get("booking").get("id").asLong();
    }

    /**
     * Tomorrow at 18:00 in Dhaka, expressed in UTC so the test does not depend on the machine's
     * zone. Deliberately outside the 10:00-14:00 morning window, so the block price is the plain
     * PS5 half-hour rate and the arithmetic below is fixed.
     */
    private static OffsetDateTime tomorrowAtSix() {
        return OffsetDateTime.now(ZoneOffset.UTC).plusDays(1)
                .withHour(12).withMinute(0).withSecond(0).withNano(0);
    }

    private HttpHeaders cashierBearer() {
        Long cashierId = post("/api/v1/staff",
                Map.of("name", "Rafi", "role", "CASHIER", "pin", "4321"), admin)
                .getBody().get("id").asLong();
        return bearerFor(cashierId, "4321");
    }

    private Long createStation() {
        return post("/api/v1/stations", Map.of("name", "PS5-01", "consoleType", "PS5"), admin)
                .getBody().get("id").asLong();
    }
}
