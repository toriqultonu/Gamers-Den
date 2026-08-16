package dev.gamersden.common.error;

import java.util.Map;

/**
 * 409 — plain {@code CONFLICT} or one of the domain 409 codes (ARCHITECTURE.md §4.4).
 * The constructor rejects codes whose status is not 409 so a miswired code fails fast in tests.
 */
public class ConflictException extends ApiException {

    public ConflictException(ErrorCode code, String message) {
        this(code, message, Map.of());
    }

    public ConflictException(ErrorCode code, String message, Map<String, Object> details) {
        super(requireConflict(code), message, details);
    }

    private static ErrorCode requireConflict(ErrorCode code) {
        if (code.status().value() != 409) {
            throw new IllegalArgumentException(code + " is not a 409 code");
        }
        return code;
    }
}
