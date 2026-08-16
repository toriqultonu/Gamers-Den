package dev.gamersden.db;

import dev.gamersden.auth.domain.StaffRole;
import dev.gamersden.billing.domain.PaymentMethod;
import dev.gamersden.billing.domain.VerifyState;
import dev.gamersden.catalog.domain.ItemCategory;
import dev.gamersden.catalog.domain.StockMovementReason;
import dev.gamersden.member.domain.PointsKind;
import dev.gamersden.member.domain.WalletKind;
import dev.gamersden.printing.domain.PrintJobStatus;
import dev.gamersden.printing.domain.PrintJobType;
import dev.gamersden.printing.domain.ReprintReason;
import dev.gamersden.session.domain.SessionState;
import dev.gamersden.settings.domain.FontScale;
import dev.gamersden.settings.domain.Theme;
import dev.gamersden.shift.domain.ExpenseCategory;
import dev.gamersden.station.domain.ConsoleType;
import dev.gamersden.station.domain.StationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drift guard: the DB CHECK constraints are the last line of defence, and Hibernate stores enums
 * by name — so a constant added to a Java enum without the matching migration would only blow up
 * at runtime, on a real sale. This reads V001 and demands each enum's constants exist verbatim as
 * one of its {@code IN (…)} lists.
 */
class BaselineDdlEnumsTest {

    private static final Pattern IN_LIST = Pattern.compile("IN\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LITERAL = Pattern.compile("'([^']*)'");

    private static final Set<Set<String>> DDL_VALUE_SETS = readBaselineCheckLists();

    static Stream<Class<? extends Enum<?>>> persistedEnums() {
        return Stream.<Class<? extends Enum<?>>>of(StaffRole.class, Theme.class, FontScale.class, ConsoleType.class,
                StationStatus.class, SessionState.class, ItemCategory.class, StockMovementReason.class,
                PaymentMethod.class, VerifyState.class, PointsKind.class, WalletKind.class,
                ExpenseCategory.class, PrintJobType.class, PrintJobStatus.class, ReprintReason.class);
    }

    @ParameterizedTest(name = "{0} matches its CHECK constraint")
    @MethodSource("persistedEnums")
    void everyPersistedEnumMatchesACheckConstraint(Class<? extends Enum<?>> type) {
        Set<String> constants = Arrays.stream(type.getEnumConstants())
                .map(Enum::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(DDL_VALUE_SETS)
                .as("%s constants %s should be a CHECK (… IN …) list in V001__baseline.sql", type.getSimpleName(), constants)
                .contains(constants);
    }

    @Test
    void theMigrationWasActuallyRead() {
        assertThat(DDL_VALUE_SETS).isNotEmpty();
    }

    private static Set<Set<String>> readBaselineCheckLists() {
        String sql = readMigration();
        Set<Set<String>> lists = new LinkedHashSet<>();
        Matcher inList = IN_LIST.matcher(sql);
        while (inList.find()) {
            Set<String> values = new LinkedHashSet<>();
            Matcher literal = LITERAL.matcher(inList.group(1));
            while (literal.find()) {
                values.add(literal.group(1));
            }
            if (!values.isEmpty()) { // skips numeric lists such as receipt_copies IN (1,2)
                lists.add(values);
            }
        }
        return lists;
    }

    private static String readMigration() {
        try (InputStream in = BaselineDdlEnumsTest.class.getResourceAsStream("/db/migration/V001__baseline.sql")) {
            if (in == null) {
                throw new IllegalStateException("V001__baseline.sql is not on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("could not read V001__baseline.sql", e);
        }
    }
}
