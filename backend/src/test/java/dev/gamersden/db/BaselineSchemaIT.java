package dev.gamersden.db;

import dev.gamersden.auth.domain.Staff;
import dev.gamersden.auth.domain.StaffRole;
import dev.gamersden.auth.repo.StaffRepository;
import dev.gamersden.station.domain.ConsoleType;
import dev.gamersden.station.domain.Pricing;
import dev.gamersden.station.repo.PricingRepository;
import dev.gamersden.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate for B02. Booting this context at all is half the assertion: the {@code test} profile runs
 * Flyway and then Hibernate {@code ddl-auto: validate}, so a context that starts proves every
 * entity matches the migrated schema. The rest checks the seed a fresh venue depends on.
 */
class BaselineSchemaIT extends AbstractIntegrationTest {

    /** Every table docs/backend-architecture.md §2 defines. Tournaments (V002) and bookings (V003) come later. */
    private static final List<String> BASELINE_TABLES = List.of(
            "staff", "terminal_settings", "stations", "pricing", "members", "shifts",
            "sessions", "session_blocks", "items", "stock_movements", "carts", "cart_lines",
            "transactions", "payment_splits", "points_ledger", "wallet_ledger", "expenses",
            "print_jobs", "token_seq", "alerts", "idempotency_keys", "sync_outbox");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StaffRepository staff;

    @Autowired
    PricingRepository pricing;

    @Test
    void flywayAppliedTheBaselineMigration() {
        List<String> applied = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class);

        assertThat(applied).containsExactly("001");
    }

    @Test
    void everyBaselineTableExists() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);

        assertThat(tables).containsAll(BASELINE_TABLES);
    }

    @Test
    void bookingEraColumnsShipInTheBaseline() {
        assertThat(columnsOf("transactions")).contains("booking_amount", "tournament_amount");
        assertThat(columnsOf("sessions")).contains("queue_entry_id");
        assertThat(columnsOf("token_seq")).contains("token_date", "next_no");
    }

    @Test
    void adminIsSeededWithABcryptPin() {
        Staff admin = staff.findByName("Admin").orElseThrow();

        assertThat(admin.getRole()).isEqualTo(StaffRole.ADMIN);
        assertThat(admin.isActive()).isTrue();
        assertThat(admin.getFailedPins()).isZero();
        assertThat(admin.getLockedUntil()).isNull();
        assertThat(admin.getCreatedAt()).isNotNull();
        assertThat(admin.getPinHash()).startsWith("$2a$");
        assertThat(new BCryptPasswordEncoder().matches("1234", admin.getPinHash())).isTrue();
    }

    @Test
    void bothConsoleRatesAreSeeded() {
        Pricing ps5 = pricing.findById(ConsoleType.PS5).orElseThrow();
        Pricing ps4 = pricing.findById(ConsoleType.PS4).orElseThrow();

        assertThat(ps5.getPerHour()).isEqualTo(120);
        assertThat(ps5.getPerHalfHour()).isEqualTo(80);
        assertThat(ps4.getPerHour()).isEqualTo(80);
        assertThat(ps4.getPerHalfHour()).isEqualTo(50);
        assertThat(pricing.count()).isEqualTo(2);
    }

    @Test
    void morningDiscountKeepsTheDocumentedDefaults() {
        Pricing ps5 = pricing.findById(ConsoleType.PS5).orElseThrow();

        // OPEN FLAG (ARCHITECTURE.md §8) — 10:00-14:00 at -25% until the venue confirms.
        assertThat(ps5.getMorningDiscountPct()).isEqualTo(25);
        assertThat(ps5.getMorningStart()).isEqualTo(LocalTime.of(10, 0));
        assertThat(ps5.getMorningEnd()).isEqualTo(LocalTime.of(14, 0));
        assertThat(ps5.getUpdatedAt()).isNotNull();
    }

    @Test
    void theCatalogueStartsEmpty() {
        // Item categories are a CHECK enum, not seed rows; the catalogue itself is venue data.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM items", Long.class)).isZero();
    }

    private List<String> columnsOf(String table) {
        return jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = ?",
                String.class, table);
    }
}
