package dev.gamersden.common.error;

import dev.gamersden.common.trace.TraceId;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * The single place a non-2xx body is produced (ARCHITECTURE.md §4.4). Every response — thrown
 * {@link ApiException}, bean-validation failure, Spring MVC fault or unhandled bug — leaves as
 * {@code {"error":{"code","message","details","traceId"}}}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex) {
        HttpStatus status = ex.code().status();
        if (status.is5xxServerError()) {
            log.error("{} -> {}", ex.code(), ex.getMessage(), ex);
        } else {
            log.debug("{} -> {}", ex.code(), ex.getMessage());
        }
        return render(status, ex.code(), ex.getMessage(), ex.details());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, Object> fields = new TreeMap<>();
        ex.getConstraintViolations()
                .forEach(v -> fields.put(v.getPropertyPath().toString(), v.getMessage()));
        return render(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "Request validation failed",
                fields.isEmpty() ? Map.of() : Map.of("fields", fields));
    }

    /** Last resort: an unexpected bug still renders the envelope, never a Spring default body. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return render(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                "Unexpected server error", Map.of());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        Map<String, Object> fields = new TreeMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(e -> fields.put(e.getField(), e.getDefaultMessage()));
        ex.getBindingResult().getGlobalErrors()
                .forEach(e -> fields.put(e.getObjectName(), e.getDefaultMessage()));
        Map<String, Object> details = fields.isEmpty() ? Map.of() : Map.of("fields", fields);
        return body(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "Request validation failed", details);
    }

    /** Everything ResponseEntityExceptionHandler catches (404, 405, 415, unreadable body, …). */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex,
                                                             Object springBody,
                                                             HttpHeaders headers,
                                                             HttpStatusCode status,
                                                             WebRequest request) {
        ErrorCode code = codeFor(status);
        if (status.is5xxServerError()) {
            log.error("{} -> {}", code, ex.getMessage(), ex);
        }
        return body(status, code, messageOf(ex, code), Map.of());
    }

    /**
     * Contract §1 defines no code for 405/406/415 and friends; they keep their HTTP status but
     * report the nearest documented code rather than inventing a new spelling.
     */
    private static ErrorCode codeFor(HttpStatusCode status) {
        if (status.is5xxServerError()) {
            return ErrorCode.INTERNAL_ERROR;
        }
        return switch (status.value()) {
            case 401 -> ErrorCode.UNAUTHORIZED;
            case 403 -> ErrorCode.FORBIDDEN;
            case 404 -> ErrorCode.NOT_FOUND;
            case 409 -> ErrorCode.CONFLICT;
            case 423 -> ErrorCode.LOCKED_PIN;
            case 429 -> ErrorCode.RATE_LIMITED;
            default -> ErrorCode.VALIDATION_FAILED;
        };
    }

    private static String messageOf(Exception ex, ErrorCode code) {
        if (code == ErrorCode.INTERNAL_ERROR) {
            return "Unexpected server error";
        }
        String message = ex.getMessage();
        return message == null || message.isBlank() ? code.name() : message;
    }

    private static ResponseEntity<ErrorResponse> render(HttpStatusCode status, ErrorCode code,
                                                        String message, Map<String, Object> details) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(envelope(code, message, details));
    }

    private static ResponseEntity<Object> body(HttpStatusCode status, ErrorCode code,
                                               String message, Map<String, Object> details) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(envelope(code, message, details));
    }

    private static ErrorResponse envelope(ErrorCode code, String message, Map<String, Object> details) {
        Map<String, Object> copy = details == null || details.isEmpty()
                ? Map.of()
                : new LinkedHashMap<>(details);
        return ErrorResponse.of(code, message, copy, TraceId.current());
    }
}
