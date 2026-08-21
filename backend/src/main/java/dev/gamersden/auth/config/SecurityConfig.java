package dev.gamersden.auth.config;

import dev.gamersden.auth.domain.JwtService;
import dev.gamersden.auth.web.ApiAccessDeniedHandler;
import dev.gamersden.auth.web.ApiAuthenticationEntryPoint;
import dev.gamersden.auth.web.JwtAuthenticationFilter;
import dev.gamersden.common.config.WebMvcConfig;
import dev.gamersden.common.error.ErrorResponseWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_AUTH_ROUTES).permitAll()
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
