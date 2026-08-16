package dev.gamersden.common.error;

import java.util.Map;

/** 423 {@code LOCKED_PIN} — 5 failed PINs lock the staff account for 15 minutes. */
public class PinLockedException extends ApiException {

    public PinLockedException(String message, Map<String, Object> details) {
        super(ErrorCode.LOCKED_PIN, message, details);
    }
}
