package dev.gamersden.auth.web;

import dev.gamersden.auth.domain.AuthService;
import dev.gamersden.common.error.UnauthorizedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /auth/login|refresh|logout} — api-contract.md §2. All three are reachable without a
 * bearer token; refresh and logout authenticate on the HttpOnly cookie instead.
 */
@RestController
@RequestMapping("/auth")
@Tag(name = "Auth")
@SecurityRequirements
public class AuthController {

    private final AuthService auth;
    private final RefreshCookies cookies;

    public AuthController(AuthService auth, RefreshCookies cookies) {
        this.auth = auth;
        this.cookies = cookies;
    }

    @PostMapping("/login")
    @Operation(summary = "Sign in with staff id + 4-digit PIN",
            description = "Returns a 15-minute access token and sets the 12-hour rotating refresh "
                    + "cookie. 401 on a wrong PIN, 423 LOCKED_PIN after 5 consecutive failures.")
    public ResponseEntity<SessionResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthService.Session session = auth.login(request.staffId(), request.pin(), request.terminal());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.set(session.refreshToken()))
                .body(SessionResponse.of(session));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Rotate the refresh cookie for a fresh access token",
            description = "The presented cookie is spent; replaying it revokes the whole session "
                    + "family and answers 401.")
    public ResponseEntity<SessionResponse> refresh(HttpServletRequest request) {
        String raw = cookies.read(request)
                .orElseThrow(() -> new UnauthorizedException("Missing refresh token"));
        AuthService.Session session = auth.refresh(raw);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.set(session.refreshToken()))
                .body(SessionResponse.of(session));
    }

    @PostMapping("/logout")
    @Operation(summary = "Revoke the refresh token and clear the cookie")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        cookies.read(request).ifPresent(auth::logout);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookies.clear())
                .build();
    }
}
