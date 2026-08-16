package dev.gamersden.printing.domain;

/** {@code print_jobs.reprint_reason} — mandatory whenever {@code isReprint} is true. */
public enum ReprintReason {
    LOST,
    DAMAGED,
    CUSTOMER_COPY,
    DISPUTE
}
