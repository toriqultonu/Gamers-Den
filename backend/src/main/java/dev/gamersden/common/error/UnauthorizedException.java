package dev.gamersden.common.error;

/** 401 {@code UNAUTHORIZED} — missing, expired or invalid credentials. */
public class UnauthorizedException extends ApiException {

    public UnauthorizedException(String message) {
        super(ErrorCode.UNAUTHORIZED, message);
    }
}
