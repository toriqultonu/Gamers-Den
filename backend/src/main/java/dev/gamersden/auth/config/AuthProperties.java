package dev.gamersden.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code gamersden.auth.*} — the numbers api-contract.md §1 fixes: 15-minute access token,
 * 12-hour rotating refresh cookie, 5 failed PINs → 15-minute lock.
 *
 * <p>{@code jwtSecret} comes from the {@code JWT_SECRET} env var (ARCHITECTURE.md §6); the value
 * in {@code application.yml} is a development placeholder and {@link SecurityConfig} refuses to
 * start with it under the {@code venue} or {@code cloud} profile.
 *
 * @param jwtSecret       HS256 signing key, at least 32 bytes
 * @param accessTtl       access-token lifetime
 * @param refreshTtl      refresh-cookie lifetime
 * @param maxPinAttempts  consecutive wrong PINs before the account locks
 * @param lockDuration    how long the lock holds
 * @param refreshCookie   cookie attributes for the refresh token
 */
@ConfigurationProperties(prefix = "gamersden.auth")
public record AuthProperties(
        String jwtSecret,
        Duration accessTtl,
        Duration refreshTtl,
        int maxPinAttempts,
        Duration lockDuration,
        RefreshCookie refreshCookie) {

    /** The placeholder shipped in {@code application.yml} — never acceptable off a dev box. */
    public static final String DEV_PLACEHOLDER_SECRET =
            "dev-only-insecure-jwt-secret-change-me-before-any-real-deployment";

    public boolean usesPlaceholderSecret() {
        return DEV_PLACEHOLDER_SECRET.equals(jwtSecret);
    }

    /**
     * @param name     cookie name
     * @param path     scoped to the auth routes — the cookie never rides along on POS calls
     * @param secure   {@code true} wherever the terminal is served over HTTPS
     * @param sameSite {@code Strict} keeps the refresh route free of cross-site CSRF
     */
    public record RefreshCookie(String name, String path, boolean secure, String sameSite) {
    }
}
