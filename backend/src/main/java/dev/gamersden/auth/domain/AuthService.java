package dev.gamersden.auth.domain;

import dev.gamersden.auth.config.AuthProperties;
import dev.gamersden.auth.repo.StaffRepository;
import dev.gamersden.common.config.VenueTime;
import dev.gamersden.common.error.ApiException;
import dev.gamersden.common.error.PinLockedException;
import dev.gamersden.common.error.UnauthorizedException;
import dev.gamersden.common.security.StaffPrincipal;
import dev.gamersden.common.spi.ShiftLookup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * PIN login, refresh rotation and logout (api-contract.md §1 "Auth").
 *
 * <p>Failure counting is deliberately durable: the login transaction carries
 * {@code noRollbackFor = ApiException.class} so the 401 that rejects a wrong PIN still commits the
 * incremented {@code failed_pins} — otherwise the lockout could never trip. PINs are never logged
 * (ARCHITECTURE.md §5.12).
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String WRONG_CREDENTIALS = "Wrong PIN";

    private final StaffRepository staff;
    private final RefreshTokenService refreshTokens;
    private final JwtService jwt;
    private final PasswordEncoder pins;
    private final ShiftLookup shifts;
    private final int maxPinAttempts;
    private final Duration lockDuration;
    private final Clock clock;

    public AuthService(StaffRepository staff,
                       RefreshTokenService refreshTokens,
                       JwtService jwt,
                       PasswordEncoder pins,
                       ShiftLookup shifts,
                       AuthProperties properties,
                       Clock clock) {
        this.staff = staff;
        this.refreshTokens = refreshTokens;
        this.jwt = jwt;
        this.pins = pins;
        this.shifts = shifts;
        this.maxPinAttempts = properties.maxPinAttempts();
        this.lockDuration = properties.lockDuration();
        this.clock = clock;
    }

    /** Access token + rotating refresh token + the staff row the login screen renders. */
    public record Session(Staff staff, StaffPrincipal principal, String accessToken,
                          RefreshTokenService.IssuedToken refreshToken, Duration accessTtl) {
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Session login(Long staffId, String pin, String terminal) {
        Staff account = staff.findById(staffId)
                .orElseThrow(() -> new UnauthorizedException(WRONG_CREDENTIALS));
        if (!account.isActive()) {
            throw new UnauthorizedException(WRONG_CREDENTIALS);
        }

        OffsetDateTime now = VenueTime.now(clock);
        requireNotLocked(account, now);

        if (!pins.matches(pin, account.getPinHash())) {
            throw registerFailure(account, now);
        }

        account.setFailedPins(0);
        account.setLockedUntil(null);
        return startSession(account, terminal);
    }

    /**
     * Rotation: the presented cookie is spent and a fresh one issued (api-contract.md §1).
     * Like {@link #login}, a 401 must not roll back the revocations the attempt just wrote.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Session refresh(String rawRefreshToken) {
        RefreshTokenService.Rotation rotation = refreshTokens.rotate(rawRefreshToken);
        Staff account = staff.findById(rotation.staffId())
                .orElseThrow(() -> new UnauthorizedException("Invalid refresh token"));
        if (!account.isActive()) {
            throw new UnauthorizedException("Invalid refresh token");
        }
        // The shift claim is re-derived: a shift may have opened or closed since login.
        StaffPrincipal principal = principalFor(account, rotation.terminal());
        return new Session(account, principal, jwt.issueAccessToken(principal),
                rotation.issued(), jwt.accessTtl());
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            refreshTokens.revoke(rawRefreshToken);
        }
    }

    private Session startSession(Staff account, String terminal) {
        StaffPrincipal principal = principalFor(account, terminal);
        RefreshTokenService.IssuedToken refreshToken = refreshTokens.issue(account.getId(), terminal);
        log.info("staff {} signed in on {} as {}", account.getId(), terminal, account.getRole());
        return new Session(account, principal, jwt.issueAccessToken(principal),
                refreshToken, jwt.accessTtl());
    }

    private StaffPrincipal principalFor(Staff account, String terminal) {
        Long shiftId = shifts.openShiftId(terminal).orElse(null);
        return new StaffPrincipal(account.getId(), account.getRole(), shiftId, terminal);
    }

    private void requireNotLocked(Staff account, OffsetDateTime now) {
        OffsetDateTime lockedUntil = account.getLockedUntil();
        if (lockedUntil == null) {
            return;
        }
        if (lockedUntil.isAfter(now)) {
            throw locked(account, lockedUntil, now);
        }
        // Lock served: the counter starts clean so the next mistake is attempt 1 again.
        account.setLockedUntil(null);
        account.setFailedPins(0);
    }

    private ApiException registerFailure(Staff account, OffsetDateTime now) {
        int failed = account.getFailedPins() + 1;
        account.setFailedPins(failed);
        if (failed >= maxPinAttempts) {
            OffsetDateTime until = now.plus(lockDuration);
            account.setLockedUntil(until);
            log.warn("staff {} locked until {} after {} failed PINs", account.getId(), until, failed);
            return locked(account, until, now);
        }
        return new UnauthorizedException(WRONG_CREDENTIALS)
                .with("attemptsRemaining", maxPinAttempts - failed);
    }

    private PinLockedException locked(Staff account, OffsetDateTime until, OffsetDateTime now) {
        long retryAfter = Math.max(0, Duration.between(now, until).toSeconds());
        return new PinLockedException(
                "PIN locked after %d failed attempts".formatted(maxPinAttempts),
                Map.of("staffId", account.getId(),
                        "lockedUntil", until.toString(),
                        "retryAfterSeconds", retryAfter));
    }
}
