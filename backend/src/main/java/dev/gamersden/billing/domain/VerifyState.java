package dev.gamersden.billing.domain;

/**
 * {@code payment_splits.verify_state}. MVP truth is a manually entered TrxID — the provider
 * states exist for the phase-2 bKash/Nagad integration (ARCHITECTURE.md §8).
 */
public enum VerifyState {
    MANUAL,
    PENDING,
    VERIFIED,
    FAILED
}
