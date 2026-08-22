package dev.gamersden.common.spi;

/**
 * The narrow write the {@code billing} package needs from {@code member} — the loyalty half of a
 * settle and of a void — without reaching for {@code MemberRepository} or either ledger
 * (ARCHITECTURE.md §3).
 *
 * <p>Implemented by {@code member/domain/MemberSettlementService}, which locks the member row and
 * writes the ledger rows and the running totals together, inside the caller's transaction. The
 * ledger is the source of truth; {@code members.wallet} and {@code members.points} are its sums
 * and are never moved without it (B08's rule, kept here).
 *
 * <p>The wallet floor is re-checked under that lock, so 409 {@code WALLET_INSUFFICIENT} is decided
 * against the balance as it is at the moment of the write, not as the bill quoted it.
 */
public interface MemberSettlement {

    /**
     * Applies a sale's loyalty movements: points spent against the bill, points earned on what was
     * actually paid, and wallet drawn down by the {@code WALLET} tenders.
     */
    void applySale(long memberId, long txId, LoyaltyMovement movement);

    /**
     * The exact inverse, for a void: redeemed points handed back, earned points taken away, wallet
     * refunded — three {@code REVERSAL} ledger rows referencing the reversal transaction.
     *
     * <p>409 {@code INSUFFICIENT_POINTS} when the member has already spent what the sale earned:
     * clamping the column would leave it disagreeing with its ledger, which is worse than
     * refusing.
     */
    void reverseSale(long memberId, long reversalTxId, LoyaltyMovement movement);

    /**
     * What one transaction moved. All three are non-negative magnitudes — the direction belongs to
     * the method, not the number.
     *
     * @param pointsRedeemed points spent as a bill discount (1 point = ৳1)
     * @param pointsEarned   {@code floor(due / 20)} on what was paid
     * @param walletSpent    the sum of the transaction's {@code WALLET} tenders
     */
    record LoyaltyMovement(int pointsRedeemed, int pointsEarned, int walletSpent) {

        public static final LoyaltyMovement NONE = new LoyaltyMovement(0, 0, 0);

        public boolean isEmpty() {
            return pointsRedeemed == 0 && pointsEarned == 0 && walletSpent == 0;
        }
    }
}
