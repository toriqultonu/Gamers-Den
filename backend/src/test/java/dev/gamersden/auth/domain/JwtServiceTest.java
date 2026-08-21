package dev.gamersden.auth.domain;

import dev.gamersden.auth.config.AuthProperties;
import dev.gamersden.common.error.ErrorCode;
import dev.gamersden.common.error.UnauthorizedException;
import dev.gamersden.common.security.StaffPrincipal;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** The four claims api-contract.md §1 fixes, plus the 15-minute expiry. */
class JwtServiceTest {

    private static final String SECRET = "test-secret-that-is-comfortably-long-enough-for-hs256";
    private static final Instant NOW = Instant.parse("2026-08-21T09:00:00Z");

    private static AuthProperties properties(String secret) {
        return new AuthProperties(secret, Duration.ofMinutes(15), Duration.ofHours(12), 5,
                Duration.ofMinutes(15),
                new AuthProperties.RefreshCookie("gd_refresh", "/api/v1/auth", false, "Strict"));
    }

    private static JwtService serviceAt(Instant instant) {
        return new JwtService(properties(SECRET), Clock.fixed(instant, ZoneOffset.UTC));
    }

    @Test
    void roundTripsEveryClaim() {
        JwtService jwt = serviceAt(NOW);
        StaffPrincipal issued = new StaffPrincipal(7L, StaffRole.MANAGER, 42L, "T2");

        StaffPrincipal parsed = jwt.parseAccessToken(jwt.issueAccessToken(issued));

        assertThat(parsed).isEqualTo(issued);
    }

    @Test
    void omitsTheShiftClaimWhenNoShiftIsOpen() {
        JwtService jwt = serviceAt(NOW);

        StaffPrincipal parsed = jwt.parseAccessToken(
                jwt.issueAccessToken(new StaffPrincipal(1L, StaffRole.ADMIN, null, "T1")));

        assertThat(parsed.shiftId()).isNull();
        assertThat(parsed.role()).isEqualTo(StaffRole.ADMIN);
    }

    @Test
    void rejectsATokenPastTheFifteenMinuteWindow() {
        String token = serviceAt(NOW).issueAccessToken(new StaffPrincipal(1L, StaffRole.ADMIN, null, "T1"));
        JwtService later = serviceAt(NOW.plus(Duration.ofMinutes(16)));

        UnauthorizedException thrown =
                catchThrowableOfType(() -> later.parseAccessToken(token), UnauthorizedException.class);

        assertThat(thrown.code()).isEqualTo(ErrorCode.UNAUTHORIZED);
        assertThat(thrown).hasMessage("Access token expired");
    }

    @Test
    void stillAcceptsATokenOneSecondBeforeExpiry() {
        String token = serviceAt(NOW).issueAccessToken(new StaffPrincipal(1L, StaffRole.CASHIER, null, "T1"));

        StaffPrincipal parsed = serviceAt(NOW.plus(Duration.ofMinutes(15)).minusSeconds(1))
                .parseAccessToken(token);

        assertThat(parsed.id()).isEqualTo(1L);
    }

    @Test
    void rejectsATokenSignedWithAnotherSecret() {
        String foreign = new JwtService(properties("a-completely-different-but-equally-long-secret"),
                Clock.fixed(NOW, ZoneOffset.UTC))
                .issueAccessToken(new StaffPrincipal(1L, StaffRole.ADMIN, null, "T1"));

        assertThatThrownBy(() -> serviceAt(NOW).parseAccessToken(foreign))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid access token");
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> serviceAt(NOW).parseAccessToken("not.a.jwt"))
                .isInstanceOf(UnauthorizedException.class);
    }
}
