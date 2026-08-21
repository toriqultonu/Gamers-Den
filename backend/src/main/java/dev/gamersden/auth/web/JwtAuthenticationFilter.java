package dev.gamersden.auth.web;

import dev.gamersden.auth.domain.JwtService;
import dev.gamersden.common.error.ApiException;
import dev.gamersden.common.error.ErrorResponseWriter;
import dev.gamersden.common.security.StaffAuthentication;
import dev.gamersden.common.security.StaffPrincipal;
import dev.gamersden.common.trace.TraceId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Turns {@code Authorization: Bearer <jwt>} into the {@link StaffAuthentication} the
 * {@code @PreAuthorize} guards read. A malformed or expired token fails the request here with the
 * 401 envelope rather than falling through as anonymous, so an expired token never reads as
 * "forbidden" — the frontend's one-silent-refresh rule depends on that distinction.
 *
 * <p>Not a Spring bean on purpose: {@code SecurityConfig} constructs it, which keeps Boot from
 * also registering it as a plain servlet filter.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final JwtService jwt;
    private final ErrorResponseWriter errors;

    public JwtAuthenticationFilter(JwtService jwt, ErrorResponseWriter errors) {
        this.jwt = jwt;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER)) {
            try {
                StaffPrincipal principal = jwt.parseAccessToken(header.substring(BEARER.length()).trim());
                SecurityContextHolder.getContext().setAuthentication(new StaffAuthentication(principal));
                TraceId.putStaffId(String.valueOf(principal.id()));
            } catch (ApiException ex) {
                SecurityContextHolder.clearContext();
                errors.write(response, ex.code(), ex.getMessage());
                return;
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
