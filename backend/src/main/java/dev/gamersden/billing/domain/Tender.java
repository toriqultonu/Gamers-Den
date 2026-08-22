package dev.gamersden.billing.domain;

/**
 * One row of the split-payment panel, on its way to becoming a {@code payment_splits} row.
 *
 * @param paymentRef the manually entered bKash/Nagad TrxID — MVP truth for those methods
 *                   (api-contract.md, "bKash / Nagad"), required on them and meaningless
 *                   elsewhere. Only its tail is ever printed or logged (invariant §5.12)
 */
public record Tender(PaymentMethod method, int amount, String paymentRef) {

    /** The provider methods whose reference is not optional — 409 {@code PAYMENT_REF_REQUIRED}. */
    public boolean needsPaymentRef() {
        return method == PaymentMethod.BKASH || method == PaymentMethod.NAGAD;
    }

    public boolean isWallet() {
        return method == PaymentMethod.WALLET;
    }
}
