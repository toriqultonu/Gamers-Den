package dev.gamersden.common.error;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base of every deliberately thrown API fault. Carries the canonical {@link ErrorCode}; the
 * {@link GlobalExceptionHandler} turns it into the {@code {error:{code,message,details,traceId}}}
 * envelope with the code's HTTP status.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> details;

    public ApiException(ErrorCode code, String message) {
        this(code, message, Map.of());
    }

    public ApiException(ErrorCode code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public ApiException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.details = Map.of();
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    /** Same fault with one extra detail entry — handy at the throw site. */
    public ApiException with(String key, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(details);
        merged.put(key, value);
        return new ApiException(code, getMessage(), merged);
    }
}
