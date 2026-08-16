package dev.gamersden.printing.domain;

/** {@code print_jobs.type} — P1–P7 plus the printer test page. */
public enum PrintJobType {
    RECEIPT,
    Z_REPORT,
    X_REPORT,
    EXPENSE_VOUCHER,
    TOURNAMENT_STUB,
    PLAY_TICKET,
    BOOKING_CONFIRMATION,
    TEST
}
