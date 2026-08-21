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
 * handler and the JWT filter. Same body shape, same {@code traceId} (ARCHITECTURE.md §4.4).
 */
@Component
public class ErrorResponseWriter {

    private final ObjectMapper mapper;

    public ErrorResponseWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void write(HttpServletResponse response, ErrorCode code, String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.reset();
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getOutputStream(),
                ErrorResponse.of(code, message, Map.of(), TraceId.current()));
    }
}
