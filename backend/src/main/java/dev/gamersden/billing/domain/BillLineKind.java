package dev.gamersden.billing.domain;

/**
 * What a bill line is charging for — the three buckets a transaction snapshot keeps apart
 * ({@code gaming_amount}, {@code fnb_amount}, {@code tournament_amount}) and the X/Z report adds
 * up by the same names (ARCHITECTURE.md §5.7).
 */
public enum BillLineKind {

    /** A 30-minute block of console time, at the rate snapshot it was sold at. */
    GAMING,

    /** A cart line — food, drink, snack or extras. */
    FNB,

    /** A tournament entry fee. Empty until B12 registers entries. */
    TOURNAMENT
}
