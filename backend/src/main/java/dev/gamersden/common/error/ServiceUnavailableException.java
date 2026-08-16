package dev.gamersden.common.error;

/** 503 — {@code PRINTER_UNAVAILABLE} or {@code SYNC_UNAVAILABLE}. */
public class ServiceUnavailableException extends ApiException {

    public ServiceUnavailableException(ErrorCode code, String message) {
        super(requireUnavailable(code), message);
    }

    private static ErrorCode requireUnavailable(ErrorCode code) {
        if (code != ErrorCode.PRINTER_UNAVAILABLE && code != ErrorCode.SYNC_UNAVAILABLE) {
            throw new IllegalArgumentException(code + " is not a 503 code");
        }
        return code;
    }
}
