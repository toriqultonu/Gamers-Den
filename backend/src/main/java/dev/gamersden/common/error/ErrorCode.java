package dev.gamersden.common.error;

import org.springframework.http.HttpStatus;

/**
 * Canonical error codes — {@code backend/ARCHITECTURE.md} §4.4 and {@code docs/api-contract.md} §1.
 * The frontend switches on these spellings; never rename or invent one.
 */
public enum ErrorCode {

    // ---- standard ----
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    CONFLICT(HttpStatus.CONFLICT),
    IDEMPOTENCY_REPLAY(HttpStatus.CONFLICT),
    LOCKED_PIN(HttpStatus.LOCKED),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    PRINTER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    SYNC_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),

    /**
     * Not in the contract's code list: the last-resort code for an unhandled server fault, so that
     * even a 500 renders the envelope instead of Spring's default body. Never thrown deliberately.
     */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),

    // ---- domain 409s ----
    STATION_BUSY(HttpStatus.CONFLICT),
    STATION_RESERVED(HttpStatus.CONFLICT),
    STATION_IN_USE(HttpStatus.CONFLICT),
    BLOCKS_CONSUMED(HttpStatus.CONFLICT),
    NO_BLOCKS(HttpStatus.CONFLICT),
    SESSION_HAS_BALANCE(HttpStatus.CONFLICT),
    OUT_OF_STOCK(HttpStatus.CONFLICT),
    DUPLICATE_NAME(HttpStatus.CONFLICT),
    DUPLICATE_PHONE(HttpStatus.CONFLICT),
    INSUFFICIENT_POINTS(HttpStatus.CONFLICT),
    SPLIT_MISMATCH(HttpStatus.CONFLICT),
    WALLET_INSUFFICIENT(HttpStatus.CONFLICT),
    PAYMENT_REF_REQUIRED(HttpStatus.CONFLICT),
    SHIFT_ALREADY_OPEN(HttpStatus.CONFLICT),
    STAFF_ON_SHIFT(HttpStatus.CONFLICT),
    TOURNAMENT_FULL(HttpStatus.CONFLICT),
    TOURNAMENT_NOT_OPEN(HttpStatus.CONFLICT),
    NOT_ENOUGH_PLAYERS(HttpStatus.CONFLICT),
    NO_FREE_CONSOLE(HttpStatus.CONFLICT),
    ALREADY_CHECKED_IN(HttpStatus.CONFLICT),
    PREBOOKING_DISABLED(HttpStatus.CONFLICT),
    CANCEL_CUTOFF_PASSED(HttpStatus.CONFLICT),
    CONSOLE_TYPE_MISMATCH(HttpStatus.CONFLICT);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
