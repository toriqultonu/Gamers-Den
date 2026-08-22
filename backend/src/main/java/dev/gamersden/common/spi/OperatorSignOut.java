package dev.gamersden.common.spi;

/**
 * The narrow write the {@code shift} package needs from {@code auth} — "closing the shift signs
 * the operator out" (api-contract.md, "Shifts &amp; expenses": Z + P2 job + logout) — without
 * reaching for {@code RefreshTokenRepository} (ARCHITECTURE.md §3).
 *
 * <p>Implemented by {@code auth/domain/RefreshTokenService}, and it means exactly what
 * {@code POST /auth/logout} means: the refresh family for that operator on that terminal is
 * revoked, so the till returns to the login screen and cannot silently renew itself into the next
 * shift. The access token it was holding is stateless and simply expires.
 */
public interface OperatorSignOut {

    /** Revokes every live refresh token {@code staffId} holds on {@code terminal}. */
    void signOutOfTerminal(long staffId, String terminal);
}
