package dev.gamersden.common.spi;

import java.util.List;

/**
 * The narrow read the {@code shift} package needs from {@code billing} — every transaction posted
 * to a shift, with the four buckets it snapshotted and the tenders that paid it — without reaching
 * for {@code TransactionRepository} (ARCHITECTURE.md §3).
 *
 * <p>Rows, not sums, cross this door on purpose. The X/Z matrix is <em>method × category</em>
 * (design.md P2/P3) and the two axes live on different tables: the buckets on the transaction, the
 * methods on its splits. Reconciling them is shift's arithmetic (B11), so what {@code billing}
 * publishes is the raw posting — which keeps the X/Z math a pure function that can be tested
 * without a database.
 *
 * <p>Nothing is filtered out. A voided sale stays exactly as it was printed and its reversal is a
 * second, negative row in the shift that refunded it (invariant §5.7), so summing everything
 * posted to a shift is what makes the drawer add up.
 */
public interface ShiftTakingsLookup {

    /**
     * The one tender that physically lands in the drawer, and so the only one a shift close counts
     * against {@code countedCash}. Named by the package that owns the enum.
     */
    String CASH = "CASH";

    /** Every transaction posted to {@code shiftId}, oldest first. */
    List<PostedTransaction> transactionsOf(long shiftId);

    /**
     * The tender methods, in the order a report lists them. Published by the package that owns the
     * enum so a report can show a zero row for a method nobody used without hard-coding the list.
     */
    List<String> tenderMethods();

    /**
     * One posted transaction.
     *
     * @param totalDue what was actually tendered: the buckets less {@code pointsRedeemed}; negative
     *                 on a refund or a void reversal
     * @param voided   true on a <em>sale</em> that was later reversed; the reversal itself is a
     *                 separate row and is never flagged
     */
    record PostedTransaction(long transactionId,
                             String publicId,
                             int gaming,
                             int fnb,
                             int tournament,
                             int booking,
                             int pointsRedeemed,
                             int pointsEarned,
                             int totalDue,
                             boolean voided,
                             List<Tender> tenders) {

        public PostedTransaction {
            tenders = List.copyOf(tenders);
        }

        /** What the buckets come to before the points discount. */
        public int gross() {
            return gaming + fnb + tournament + booking;
        }
    }

    /** One tender row — {@code CASH}, {@code BKASH}, {@code NAGAD}, {@code WALLET}. */
    record Tender(String method, int amount) {
    }
}
