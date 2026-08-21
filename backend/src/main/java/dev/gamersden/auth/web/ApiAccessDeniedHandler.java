package dev.gamersden.auth.web;

import dev.gamersden.common.error.ErrorCode;
import dev.gamersden.common.error.ErrorResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Authenticated but out of role (a cashier on an Admin route) → 403 {@code FORBIDDEN} envelope.
 * The permission matrix is API-enforced; UI hiding is cosmetic (api-contract.md §1).
 */
@Component
public class ApiAccessDeniedHandler implements AccessDeniedHandler {

    private final ErrorResponseWriter errors;

    public ApiAccessDeniedHandler(ErrorResponseWriter errors) {
        this.errors = errors;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        errors.write(response, ErrorCode.FORBIDDEN, "Your role does not allow this action");
    }
}
