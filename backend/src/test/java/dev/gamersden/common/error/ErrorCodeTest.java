package dev.gamersden.common.error;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The frontend switches on these spellings and statuses (ARCHITECTURE.md §4.4) — a rename or a
 * status drift is a breaking change, so it must break the build here first.
 */
class ErrorCodeTest {

    private static final List<String> DOMAIN_CONFLICT_CODES = List.of(
            "STATION_BUSY", "STATION_RESERVED", "STATION_IN_USE", "BLOCKS_CONSUMED", "NO_BLOCKS",
            "SESSION_HAS_BALANCE", "OUT_OF_STOCK", "DUPLICATE_NAME", "DUPLICATE_PHONE",
            "INSUFFICIENT_POINTS", "SPLIT_MISMATCH", "WALLET_INSUFFICIENT", "PAYMENT_REF_REQUIRED",
            "SHIFT_ALREADY_OPEN", "STAFF_ON_SHIFT", "TOURNAMENT_FULL", "TOURNAMENT_NOT_OPEN",
            "NOT_ENOUGH_PLAYERS", "NO_FREE_CONSOLE", "ALREADY_CHECKED_IN", "PREBOOKING_DISABLED",
            "CANCEL_CUTOFF_PASSED", "CONSOLE_TYPE_MISMATCH");

    @Test
    void standardCodesKeepTheirDocumentedStatuses() {
        assertThat(ErrorCode.VALIDATION_FAILED.status().value()).isEqualTo(400);
        assertThat(ErrorCode.UNAUTHORIZED.status().value()).isEqualTo(401);
        assertThat(ErrorCode.FORBIDDEN.status().value()).isEqualTo(403);
        assertThat(ErrorCode.NOT_FOUND.status().value()).isEqualTo(404);
        assertThat(ErrorCode.CONFLICT.status().value()).isEqualTo(409);
        assertThat(ErrorCode.IDEMPOTENCY_REPLAY.status().value()).isEqualTo(409);
        assertThat(ErrorCode.LOCKED_PIN.status().value()).isEqualTo(423);
        assertThat(ErrorCode.RATE_LIMITED.status().value()).isEqualTo(429);
        assertThat(ErrorCode.PRINTER_UNAVAILABLE.status().value()).isEqualTo(503);
        assertThat(ErrorCode.SYNC_UNAVAILABLE.status().value()).isEqualTo(503);
    }

    @ParameterizedTest
    @ValueSource(strings = {"STATION_BUSY", "CONSOLE_TYPE_MISMATCH", "SPLIT_MISMATCH"})
    void domainCodesAreConflicts(String name) {
        assertThat(ErrorCode.valueOf(name).status().value()).isEqualTo(409);
    }

    @Test
    void everyDocumentedDomainCodeExistsAndIsA409() {
        DOMAIN_CONFLICT_CODES.forEach(name ->
                assertThat(ErrorCode.valueOf(name).status().value())
                        .as(name)
                        .isEqualTo(409));
    }

    @Test
    void conflictExceptionRejectsNonConflictCodes() {
        assertThatThrownBy(() -> new ConflictException(ErrorCode.NOT_FOUND, "wrong"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void serviceUnavailableExceptionRejectsNon503Codes() {
        assertThatThrownBy(() -> new ServiceUnavailableException(ErrorCode.CONFLICT, "wrong"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withAddsDetailsWithoutLosingTheCode() {
        ApiException base = new ApiException(ErrorCode.OUT_OF_STOCK, "Coke is out");

        ApiException enriched = base.with("itemId", 7);

        assertThat(enriched.code()).isEqualTo(ErrorCode.OUT_OF_STOCK);
        assertThat(enriched.details()).containsEntry("itemId", 7);
        assertThat(base.details()).isEmpty();
    }
}
