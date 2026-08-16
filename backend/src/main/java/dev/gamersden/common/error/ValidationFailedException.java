package dev.gamersden.common.error;

import java.util.Map;

/** 400 {@code VALIDATION_FAILED} for rules bean validation cannot express. */
public class ValidationFailedException extends ApiException {

    public ValidationFailedException(String message) {
        super(ErrorCode.VALIDATION_FAILED, message);
    }

    public ValidationFailedException(String message, Map<String, Object> details) {
        super(ErrorCode.VALIDATION_FAILED, message, details);
    }

    public static ValidationFailedException onField(String field, String message) {
        return new ValidationFailedException(message, Map.of("field", field));
    }
}
