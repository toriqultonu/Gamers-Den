package dev.gamersden.common.error;

/** 429 {@code RATE_LIMITED}. */
public class RateLimitedException extends ApiException {

    public RateLimitedException(String message) {
        super(ErrorCode.RATE_LIMITED, message);
    }
}
