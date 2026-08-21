package dev.gamersden.auth.web;

import dev.gamersden.auth.domain.AuthService;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What {@code /auth/login} and {@code /auth/refresh} return. The refresh token is not in the body —
 * it only ever travels as the HttpOnly cookie.
 *
 * @param expiresIn access-token lifetime in seconds (15 min per api-contract.md §1)
 * @param shiftId   the terminal's open shift, mirroring the token's {@code shiftId} claim
 */
@Schema(name = "SessionResponse")
public record SessionResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        StaffView staff,
        Long shiftId,
        String terminal) {

    public static SessionResponse of(AuthService.Session session) {
        return new SessionResponse(
                session.accessToken(),
                "Bearer",
                session.accessTtl().toSeconds(),
                StaffView.of(session.staff()),
                session.principal().shiftId(),
                session.principal().terminal());
    }
}
