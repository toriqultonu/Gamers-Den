package dev.gamersden.common.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gamersden.common.trace.TraceId;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Renders the error envelope from inside the servlet filter chain, where
 * {@link GlobalExceptionHandler} cannot reach — the security entry point, the access-denied
 * handler, the JWT filter and the idempotency filter. Same body shape, same {@code traceId}
 * (ARCHITECTURE.md §4.4).
 */
@Component
public class ErrorResponseWriter {

    private final ObjectMapper mapper;

    public ErrorResponseWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void write(HttpServletResponse response, ErrorCode code, String message) throws IOException {
        write(response, code, message, Map.of());
    }

    public void write(HttpServletResponse response, ErrorCode code, String message,
                      Map<String, Object> details) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        String traceId = TraceId.current();
        response.reset();
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(TraceId.HEADER, traceId);
        mapper.writeValue(response.getOutputStream(),
                ErrorResponse.of(code, message, details, traceId));
    }
}
