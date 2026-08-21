package dev.gamersden.auth.web;

import dev.gamersden.auth.config.AuthProperties;
import dev.gamersden.auth.domain.RefreshTokenService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * Builds and reads the refresh cookie. HttpOnly so no script can lift it, {@code SameSite=Strict}
 * and scoped to {@code /api/v1/auth} so it never rides along on POS calls and cannot be driven
 * cross-site — which is why the stateless chain needs no CSRF token.
 */
@Component
public class RefreshCookies {

    private final AuthProperties.RefreshCookie config;
    private final Duration ttl;

    public RefreshCookies(AuthProperties properties) {
        this.config = properties.refreshCookie();
        this.ttl = properties.refreshTtl();
    }

    public String name() {
        return config.name();
    }

    public Optional<String> read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(cookie -> config.name().equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }

    public String set(RefreshTokenService.IssuedToken token) {
        return base(token.rawValue()).maxAge(ttl).build().toString();
    }

    public String clear() {
        return base("").maxAge(0).build().toString();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(config.name(), value)
                .httpOnly(true)
                .secure(config.secure())
                .sameSite(config.sameSite())
                .path(config.path());
    }
}
