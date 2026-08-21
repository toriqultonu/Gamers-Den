package dev.gamersden.auth.web;

import dev.gamersden.common.error.ErrorCode;
import dev.gamersden.common.error.ErrorResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** No credentials on a guarded route → 401 in the standard envelope, never a Spring default body. */
@Component
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ErrorResponseWriter errors;

    public ApiAuthenticationEntryPoint(ErrorResponseWriter errors) {
        this.errors = errors;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        errors.write(response, ErrorCode.UNAUTHORIZED, "Authentication required");
    }
}
