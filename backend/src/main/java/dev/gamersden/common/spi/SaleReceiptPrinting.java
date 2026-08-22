package dev.gamersden.common.spi;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The narrow write {@code billing} (and, from B15, {@code booking}) needs from {@code printing} —
 * the sale ticket a settle queues — without reaching for {@code PrintJobRepository}
 * (ARCHITECTURE.md §3).
 *
 * <p>Invariant §5.3: the print job is created <em>inside the money transaction</em>, so a replayed
 * settle returns the same {@code printJobId} and a rolled-back settle leaves no paper behind.
 * Invariant §5.5: the bytes are rendered once, here, and stored — a retry re-sends them
 * unchanged.
 *
 * <p>What is passed is the receipt's content, not its layout. Until B17 the renderer behind this
 * door writes a plain-text placeholder; swapping in the real ESC/POS P1 template changes nothing
 * on this side.
 */
public interface SaleReceiptPrinting {

    /** Renders and queues one sale ticket, in the caller's transaction. */
    long issueSaleReceipt(SaleReceipt receipt);

    /**
     * Everything P1 prints (design.md §5).
     *
     * @param heading    the station the sale belongs to, or "Counter sale"
     * @param deviceId   which printer the job is queued for — the terminal owns its USB printer
     * @param operatorId the cashier, for the CASHIER meta row and the print-job audit
     * @param total      what the tenders come to: charges minus any points discount
     */
    record SaleReceipt(long transactionId,
                       String publicId,
                       String heading,
                       String deviceId,
                       long operatorId,
                       OffsetDateTime at,
                       List<Line> lines,
                       int total,
                       List<Tender> tenders,
                       int pointsRedeemed,
                       int pointsEarned,
                       Integer pointsBalance) {
    }

    /** One printed line — {@code GAMING 3x30M}, {@code PEPSI 250ML x2}. */
    record Line(String label, int qty, int amount) {
    }

    /**
     * One tender row.
     *
     * @param paymentRef the provider TrxID on a bKash/Nagad row; only its tail is ever printed or
     *                   logged (invariant §5.12)
     */
    record Tender(String method, int amount, String paymentRef) {
    }
}
