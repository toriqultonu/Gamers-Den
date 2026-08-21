package dev.gamersden.auth.domain;

import dev.gamersden.auth.config.AuthProperties;
import dev.gamersden.common.error.UnauthorizedException;
import dev.gamersden.common.security.StaffPrincipal;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Mints and verifies the 15-minute access token. Claims are exactly the four api-contract.md §1
 * names — {@code sub}, {@code role}, {@code shiftId?}, {@code terminal} — so a request needs no
 * database round-trip to know who is calling.
 */
@Service
public class JwtService {

    static final String CLAIM_ROLE = "role";
    static final String CLAIM_SHIFT_ID = "shiftId";
    static final String CLAIM_TERMINAL = "terminal";

    private final SecretKey key;
    private final Duration accessTtl;
    private final Clock clock;

    public JwtService(AuthProperties properties, Clock clock) {
        this.key = Keys.hmacShaKeyFor(properties.jwtSecret().getBytes(StandardCharsets.UTF_8));
        this.accessTtl = properties.accessTtl();
        this.clock = clock;
    }

    public Duration accessTtl() {
        return accessTtl;
    }

    public String issueAccessToken(StaffPrincipal principal) {
        Instant now = clock.instant();
        var builder = Jwts.builder()
                .subject(String.valueOf(principal.id()))
                .claim(CLAIM_ROLE, principal.role().name())
                .claim(CLAIM_TERMINAL, principal.terminal())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtl)));
        if (principal.shiftId() != null) {
            builder.claim(CLAIM_SHIFT_ID, principal.shiftId());
        }
        return builder.signWith(key).compact();
    }

    /**
     * @throws UnauthorizedException on any signature, expiry or shape problem — the caller turns
     *         that into the 401 envelope; the reason is never leaked beyond expired-vs-invalid.
     */
    public StaffPrincipal parseAccessToken(String token) {
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(key)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (io.jsonwebtoken.ExpiredJwtException ex) {
            throw new UnauthorizedException("Access token expired");
        } catch (JwtException | IllegalArgumentException ex) {
            throw new UnauthorizedException("Invalid access token");
        }
        try {
            Number shiftId = claims.get(CLAIM_SHIFT_ID, Number.class);
            return new StaffPrincipal(
                    Long.valueOf(claims.getSubject()),
                    StaffRole.valueOf(claims.get(CLAIM_ROLE, String.class)),
                    shiftId == null ? null : shiftId.longValue(),
                    claims.get(CLAIM_TERMINAL, String.class));
        } catch (RuntimeException ex) {
            throw new UnauthorizedException("Invalid access token");
        }
    }
}
