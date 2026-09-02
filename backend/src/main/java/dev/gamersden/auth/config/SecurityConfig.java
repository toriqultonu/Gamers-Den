package dev.gamersden.auth.config;

import dev.gamersden.auth.domain.JwtService;
import dev.gamersden.auth.web.ApiAccessDeniedHandler;
import dev.gamersden.auth.web.ApiAuthenticationEntryPoint;
import dev.gamersden.auth.web.JwtAuthenticationFilter;
import dev.gamersden.common.config.WebMvcConfig;
import dev.gamersden.common.error.ErrorResponseWriter;
import dev.gamersden.settings.web.TerminalSettingsController;
import dev.gamersden.sync.domain.SyncPusher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import jakarta.servlet.DispatcherType;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * The stateless JWT filter chain and the method-security switch every later controller relies on
 * (ARCHITECTURE.md §4.6). No sessions, no form login, no CSRF token: the access token is a bearer
 * header and the refresh cookie is {@code SameSite=Strict} and scoped to the auth routes.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);
    private static final int BCRYPT_STRENGTH = 10;
    private static final int MIN_SECRET_BYTES = 32;

    /** Reachable without a token: health, the OpenAPI document the FE generates types from, login. */
    private static final String[] PUBLIC_PATHS = {
            "/actuator/health", "/actuator/health/**", "/actuator/info",
            "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**",
            "/error"
    };

    private static final String[] PUBLIC_AUTH_ROUTES = {
            WebMvcConfig.API_BASE_PATH + "/auth/login",
            WebMvcConfig.API_BASE_PATH + "/auth/refresh",
            WebMvcConfig.API_BASE_PATH + "/auth/logout"
    };

    /**
     * The login background (B21). S1 paints it under the brand statement before anyone has a
     * token (design.md §1), so the one route that serves the picture has to be reachable without
     * one — reads only, by an id minted at upload and stored nowhere a stranger can list. The
     * settings around it stay authenticated: reading them needs a role, writing them needs Admin.
     */
    private static final String[] PUBLIC_IMAGE_ROUTES = {
            WebMvcConfig.API_BASE_PATH + TerminalSettingsController.LOGIN_BG_PATH + "/*"
    };

    /**
     * The cloud's sync receiver (B22). Its caller is the venue box — a machine with no staff, no
     * shift and no terminal — so the bearer chain has nothing to authenticate. It carries the
     * shared {@code SYNC_TOKEN} instead, checked constant-time by the controller, which is the
     * only reason this route is out of the chain's hands. It exists at all only under the
     * {@code cloud} profile; everywhere else the path 404s.
     */
    private static final String[] SYNC_RECEIVER_ROUTES = { SyncPusher.PUSH_PATH };

    public SecurityConfig(AuthProperties properties, Environment environment) {
        assertUsableSecret(properties, environment);
    }

    @Bean
    public SecurityFilterChain apiSecurity(HttpSecurity http,
                                           JwtService jwt,
                                           ErrorResponseWriter errors,
                                           ApiAuthenticationEntryPoint entryPoint,
                                           ApiAccessDeniedHandler accessDenied) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // The continuation of a request that was already authorized on its way in.
                        // GET /events answers with an SseEmitter, so the container dispatches the
                        // request a second time when the stream ends — with no SecurityContext,
                        // because the token was on the original dispatch. Re-authorizing that
                        // continuation would deny every SSE subscription as it closed.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_AUTH_ROUTES).permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_IMAGE_ROUTES).permitAll()
                        .requestMatchers(HttpMethod.POST, SYNC_RECEIVER_ROUTES).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDenied))
                .addFilterBefore(new JwtAuthenticationFilter(jwt, errors),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /** BCrypt strength 10 — the seeded Admin hash in V001 is {@code $2a$10$…}. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_STRENGTH);
    }

    private static void assertUsableSecret(AuthProperties properties, Environment environment) {
        String secret = properties.jwtSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "gamersden.auth.jwt-secret (env JWT_SECRET) must be at least "
                            + MIN_SECRET_BYTES + " bytes for HS256");
        }
        if (!properties.usesPlaceholderSecret()) {
            return;
        }
        List<String> profiles = Arrays.asList(environment.getActiveProfiles());
        if (profiles.contains("venue") || profiles.contains("cloud")) {
            throw new IllegalStateException(
                    "JWT_SECRET is still the development placeholder — set a real secret before "
                            + "running the venue or cloud profile");
        }
        log.warn("Using the development JWT secret. Set JWT_SECRET before any real deployment.");
    }
}
