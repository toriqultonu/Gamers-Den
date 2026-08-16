package dev.gamersden.common.error;

/** 403 {@code FORBIDDEN} — authenticated, but the role lacks the capability (api-contract.md §1). */
public class ForbiddenException extends ApiException {

    public ForbiddenException(String message) {
        super(ErrorCode.FORBIDDEN, message);
    }
}
