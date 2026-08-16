package dev.gamersden.billing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** {@code payment_splits} — one row per tender. Amount is non-zero and negative on refunds. */
@Entity
@Table(name = "payment_splits")
public class PaymentSplit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tx_id", nullable = false)
    private Long txId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentMethod method;

    @Column(nullable = false)
    private int amount;

    /** Provider reference; only the last 4 characters are ever logged. */
    @Column(name = "payment_ref")
    private String paymentRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "verify_state", nullable = false)
    private VerifyState verifyState = VerifyState.MANUAL;

    protected PaymentSplit() {
    }

    public PaymentSplit(Long txId, PaymentMethod method, int amount, String paymentRef) {
        this.txId = txId;
        this.method = method;
        this.amount = amount;
        this.paymentRef = paymentRef;
    }

    public Long getId() {
        return id;
    }

    public Long getTxId() {
        return txId;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public int getAmount() {
        return amount;
    }

    public String getPaymentRef() {
        return paymentRef;
    }

    public VerifyState getVerifyState() {
        return verifyState;
    }

    public void setVerifyState(VerifyState verifyState) {
        this.verifyState = verifyState;
    }
}
