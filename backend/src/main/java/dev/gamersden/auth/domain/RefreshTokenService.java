package dev.gamersden.auth.domain;

import dev.gamersden.auth.config.AuthProperties;
import dev.gamersden.auth.repo.RefreshTokenRepository;
import dev.gamersden.common.config.VenueTime;
import dev.gamersden.common.error.ApiException;
import dev.gamersden.common.error.UnauthorizedException;
import dev.gamersden.common.spi.OperatorSignOut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * Issues, rotates and revokes the refresh cookie. The cookie value is 256 bits of opaque random —
 * not a JWT — because revocation has to be immediate and rotation has to be detectable.
 *
 * <p>Rotation is one-shot: spending a token revokes it and links it to its successor. Presenting a
 * spent token again is treated as theft, not as a race — the whole live family for that
 * staff+terminal is revoked and the caller has to sign in with a PIN again.
 */
@Service
public class RefreshTokenService implements OperatorSignOut {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository tokens;
    private final Duration refreshTtl;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository tokens, AuthProperties properties, Clock clock) {
        this.tokens = tokens;
        this.refreshTtl = properties.refreshTtl();
        this.clock = clock;
    }

    public Duration refreshTtl() {
        return refreshTtl;
    }

    /** The raw value only ever exists here and in the {@code Set-Cookie} header. */
    public record IssuedToken(String rawValue, OffsetDateTime expiresAt) {
    }

    @Transactional
    public IssuedToken issue(Long staffId, String terminal) {
        return issueInternal(staffId, terminal).asIssuedToken();
    }

    private Minted issueInternal(Long staffId, String terminal) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        OffsetDateTime expiresAt = VenueTime.now(clock).plus(refreshTtl);
        RefreshToken saved = tokens.save(new RefreshToken(staffId, terminal, hash(raw), expiresAt));
        log.debug("refresh token {} issued for staff {} on {}", saved.getId(), staffId, terminal);
        return new Minted(raw, saved);
    }

    private record Minted(String rawValue, RefreshToken row) {
        IssuedToken asIssuedToken() {
            return new IssuedToken(rawValue, row.getExpiresAt());
        }
    }

    /**
     * Spends {@code rawValue} and returns its successor.
     *
     * <p>{@code noRollbackFor} matters here: the 401 that rejects a replayed cookie must still
     * commit the family revocation it just wrote, or reuse detection would undo itself.
     *
     * @throws UnauthorizedException when the cookie is unknown, expired, or already spent
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Rotation rotate(String rawValue) {
        OffsetDateTime now = VenueTime.now(clock);
        RefreshToken current = tokens.findByTokenHash(hash(rawValue))
                .orElseThrow(() -> new UnauthorizedException("Invalid refresh token"));

        if (current.isRevoked()) {
            revokeFamily(current, now);
            log.warn("refresh token reuse detected for staff {} on {} — family revoked",
                    current.getStaffId(), current.getTerminal());
            throw new UnauthorizedException("Invalid refresh token");
        }
        if (current.isExpiredAt(now)) {
            current.revoke(now);
            throw new UnauthorizedException("Refresh token expired");
        }

        current.revoke(now);
        Minted next = issueInternal(current.getStaffId(), current.getTerminal());
        current.rotateTo(next.row().getId());
        return new Rotation(current.getStaffId(), current.getTerminal(), next.asIssuedToken());
    }

    public record Rotation(Long staffId, String terminal, IssuedToken issued) {
    }

    /** Logout. Silent when the cookie is unknown — a signed-out caller is signed out either way. */
    @Transactional
    public void revoke(String rawValue) {
        OffsetDateTime now = VenueTime.now(clock);
        tokens.findByTokenHash(hash(rawValue)).ifPresent(token -> token.revoke(now));
    }

    /**
     * Closing a shift signs its operator out of that till (api-contract.md, "Shifts &amp;
     * expenses"). Terminal-scoped rather than account-wide: the same cashier may legitimately be
     * signed in elsewhere, and a manager closing someone else's shift must not be signed out with
     * them.
     */
    @Override
    @Transactional
    public void signOutOfTerminal(long staffId, String terminal) {
        OffsetDateTime now = VenueTime.now(clock);
        List<RefreshToken> live = tokens.findByStaffIdAndTerminalAndRevokedAtIsNull(staffId, terminal);
        live.forEach(token -> token.revoke(now));
        if (!live.isEmpty()) {
            log.info("staff {} signed out of {} — {} refresh token(s) revoked",
                    staffId, terminal, live.size());
        }
    }

    /** Cuts every live session for a staff member — used when the account is disabled or deleted. */
    @Transactional
    public void revokeAllForStaff(Long staffId) {
        OffsetDateTime now = VenueTime.now(clock);
        tokens.findByStaffIdAndRevokedAtIsNull(staffId).forEach(token -> token.revoke(now));
    }

    private void revokeFamily(RefreshToken current, OffsetDateTime now) {
        List<RefreshToken> live = tokens.findByStaffIdAndTerminalAndRevokedAtIsNull(
                current.getStaffId(), current.getTerminal());
        live.forEach(token -> token.revoke(now));
    }

    static String hash(String rawValue) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawValue.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
