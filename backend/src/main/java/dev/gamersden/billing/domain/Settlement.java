package dev.gamersden.billing.domain;

import dev.gamersden.common.error.ConflictException;
import dev.gamersden.common.error.ErrorCode;
import dev.gamersden.common.error.ValidationFailedException;
import dev.gamersden.common.spi.MemberSettlement.LoyaltyMovement;
import dev.gamersden.common.util.Money;

import java.util.List;
import java.util.Map;

/**
 * Everything {@code POST /payments} decides before it writes a single row: what is owed, what the
 * points take off it, what the tenders must therefore come to, and what the sale earns back.
 *
 * <p>Pure arithmetic over its inputs — no repository, no clock, no {@code SecurityContext} — so
 * every rule below is unit-testable without a database, and by the time {@link PaymentService}
 * starts writing there is nothing left to reject. That ordering is the point: a settle that fails
 * validation must leave <em>zero</em> writes behind, and the cheapest way to guarantee that is for
 * the refusals to happen before the first insert.
 *
 * <p>The rules, in the order they are applied:
 *
 * <ol>
 *   <li><strong>Every tender is positive.</strong> A settle takes money; refunds are separate
 *       negative transactions, never a negative row inside a sale (invariant §5.7).</li>
 *   <li><strong>bKash and Nagad carry a reference.</strong> MVP truth for those methods is a
 *       manually entered TrxID, so a row without one is 409 {@code PAYMENT_REF_REQUIRED}.</li>
 *   <li><strong>Points are a capped discount</strong>, {@code min(requested, points, gross)}
 *       (api-contract.md §1). Capping rather than refusing is deliberate: asking for more points
 *       than the member holds simply buys less discount, and the tenders then no longer match the
 *       total, so the request is refused by rule 5 with nothing written.</li>
 *   <li><strong>The wallet is a floor, not a limit to be checked twice.</strong> The quoted
 *       balance is checked here so the panel gets a clean 409 {@code WALLET_INSUFFICIENT}; the
 *       binding check happens again under the member's row lock in {@code member}, because
 *       between the quote and the write another terminal may have spent it.</li>
 *   <li><strong>The tenders must equal the total exactly</strong> — 409 {@code SPLIT_MISMATCH}
 *       otherwise, with both figures in {@code details} so the panel can re-quote. A bill covered
 *       entirely by points takes no tenders at all: {@code payment_splits} carries
 *       {@code CHECK (amount <> 0)}, so "paid nothing" is an empty list, never a zero row.</li>
 *   <li><strong>Earning is on what was actually paid</strong> — {@code floor(due / 20)} of the
 *       post-discount total, so redeeming points cannot quietly re-earn them.</li>
 * </ol>
 *
 * @param pointsRedeemed what the discount came to after capping — may be less than was asked for
 * @param totalDue       what the tenders sum to: {@code charges.gross() - pointsRedeemed}
 * @param pointsEarned   {@code floor(totalDue / 20)}, or 0 with no member attached
 * @param walletSpent    the sum of the {@code WALLET} tenders, drawn from the member's balance
 */
public record Settlement(Charges charges,
                         Long memberId,
                         int pointsRedeemed,
                         int totalDue,
                         int pointsEarned,
                         int walletSpent,
                         List<Tender> tenders) {

    public Settlement {
        tenders = List.copyOf(tenders);
    }

    public static Settlement of(Charges charges,
                                Bill.Member member,
                                Integer requestedRedeem,
                                List<Tender> tenders) {
        List<Tender> requested = tenders == null ? List.of() : List.copyOf(tenders);
        requested.forEach(Settlement::validate);

        int walletSpent = sumOf(requested.stream().filter(Tender::isWallet).toList());
        if (walletSpent > 0 && !member.attached()) {
            throw ValidationFailedException.onField("splits",
                    "A WALLET tender needs a member on the session");
        }
        if (walletSpent > member.wallet()) {
            throw new ConflictException(ErrorCode.WALLET_INSUFFICIENT,
                    "The wallet holds %d BDT — it cannot cover %d".formatted(member.wallet(), walletSpent),
                    Map.of("wallet", member.wallet(), "requested", walletSpent));
        }

        int redeemed = redemption(requestedRedeem, member, charges.gross());
        int totalDue = charges.gross() - redeemed;
        int provided = sumOf(requested);
        if (provided != totalDue) {
            throw new ConflictException(ErrorCode.SPLIT_MISMATCH,
                    "The tenders come to %d BDT but %d is due".formatted(provided, totalDue),
                    Map.of("expected", totalDue, "provided", provided,
                            "gross", charges.gross(), "pointsRedeemed", redeemed));
        }

        return new Settlement(charges,
                member.attached() ? member.id() : null,
                redeemed,
                totalDue,
                member.attached() ? Money.pointsEarned(totalDue) : 0,
                walletSpent,
                requested);
    }

    /**
     * {@code min(requested, points on hand, what is owed)}. The cap against the bill is what keeps
     * a transaction from going negative through loyalty alone; refunds are their own transactions.
     */
    private static int redemption(Integer requested, Bill.Member member, int gross) {
        if (requested == null || requested == 0) {
            return 0;
        }
        if (requested < 0) {
            throw ValidationFailedException.onField("redeemPoints", "redeemPoints cannot be negative");
        }
        if (!member.attached()) {
            throw ValidationFailedException.onField("redeemPoints",
                    "There is no member on this sale to redeem points from");
        }
        return Money.cappedRedemption(Math.min(requested, member.points()), gross);
    }

    private static void validate(Tender tender) {
        if (tender.method() == null) {
            throw ValidationFailedException.onField("splits", "Every tender needs a method");
        }
        if (tender.amount() <= 0) {
            throw ValidationFailedException.onField("splits",
                    "A tender is money taken — %s for %d is not"
                            .formatted(tender.method(), tender.amount()));
        }
        if (tender.needsPaymentRef()
                && (tender.paymentRef() == null || tender.paymentRef().isBlank())) {
            throw new ConflictException(ErrorCode.PAYMENT_REF_REQUIRED,
                    "%s needs the transaction reference from the customer's app"
                            .formatted(tender.method()),
                    Map.of("method", tender.method().name()));
        }
    }

    private static int sumOf(List<Tender> tenders) {
        return tenders.stream().mapToInt(Tender::amount).sum();
    }

    /** What the {@code member} package has to move for this sale. */
    public LoyaltyMovement loyalty() {
        return memberId == null
                ? LoyaltyMovement.NONE
                : new LoyaltyMovement(pointsRedeemed, pointsEarned, walletSpent);
    }
}
