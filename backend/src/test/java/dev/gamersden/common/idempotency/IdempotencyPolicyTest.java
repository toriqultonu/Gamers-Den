package dev.gamersden.common.idempotency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guarded set is a contract clause, not a preference — api-contract.md §1 names exactly these
 * routes, and ARCHITECTURE.md §5.2 repeats them.
 */
class IdempotencyPolicyTest {

    private final IdempotencyPolicy policy = IdempotencyPolicy.contractDefaults();

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/payments",
            "/api/v1/print-jobs",
            "/api/v1/sessions/42/blocks",
            "/api/v1/members/7/wallet/topup",
            "/api/v1/members/7/wallet/redeem-points",
            "/api/v1/tournaments/3/entries",
            "/api/v1/bookings",
            "/api/v1/bookings/9/cancel",
            "/api/v1/play-tickets"
    })
    @DisplayName("every money or print route from the contract demands a key")
    void guardsContractRoutes(String path) {
        assertThat(policy.guards("POST", path)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/bookings/",
            "/api/v1/payments/"
    })
    @DisplayName("a trailing slash is still the same route")
    void ignoresTrailingSlash(String path) {
        assertThat(policy.guards("POST", path)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/sessions",                    // creating a session is not a money write
            "/api/v1/sessions/42/clock",
            "/api/v1/sessions/42/end",
            "/api/v1/payments/9/void",             // Manager-only reversal, not on the guarded list
            "/api/v1/bookings/9/check-in",
            "/api/v1/auth/login",
            "/api/v1/carts",
            "/api/v1/expenses",
            "/api/v1/shifts"
    })
    @DisplayName("routes the contract left off the list stay unguarded")
    void leavesOtherRoutesAlone(String path) {
        assertThat(policy.guards("POST", path)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "PUT", "PATCH", "DELETE"})
    @DisplayName("only POST is guarded — reads and edits carry no key")
    void guardsOnlyPost(String method) {
        assertThat(policy.guards(method, "/api/v1/payments")).isFalse();
    }

    @org.junit.jupiter.api.Test
    @DisplayName("the contract list is the eight routes of api-contract.md §1")
    void listsEveryContractRoute() {
        assertThat(policy.routes()).hasSize(8);
    }
}
