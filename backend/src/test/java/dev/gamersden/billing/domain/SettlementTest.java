package dev.gamersden.billing.domain;

import dev.gamersden.common.error.ApiException;
import dev.gamersden.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The arithmetic and the refusals of {@code POST /payments}, with no database in sight.
 *
 * <p>These are the checks that have to happen <em>before</em> the first insert: a settle that is
 * going to be refused must leave nothing behind, and the cheapest guarantee of that is for every
 * refusal to be decidable from the request and the quoted bill alone. {@code PaymentSettleIT}
 * proves the same codes over real rows; what is proved here is that the numbers are right.
 */
class SettlementTest {

    private static final Bill.Member WALK_IN = Bill.Member.NONE;

    /** 500 points on hand, ৳400 in the wallet — enough to exercise both floors. */
    private static final Bill.Member RIFAT = new Bill.Member(7L, "Rifat Hasan", 500, 400);

    private static Tender cash(int amount) {
        return new Tender(PaymentMethod.CASH, amount, null);
    }

    private static Tender bkash(int amount, String ref) {
        return new Tender(PaymentMethod.BKASH, amount, ref);
    }

    private static Tender wallet(int amount) {
        return new Tender(PaymentMethod.WALLET, amount, null);
    }

    private static ErrorCode codeOf(Throwable thrown) {
        return ((ApiException) thrown).code();
    }

    @Nested
    @DisplayName("what is owed")
    class Totals {

        @Test
        @DisplayName("with no points in play the tenders simply cover the gross")
        void grossIsTheTotal() {
            Charges charges = new Charges(160, 120, 0, 0);

            Settlement settlement = Settlement.of(charges, WALK_IN, null, List.of(cash(280)));

            assertThat(settlement.totalDue()).isEqualTo(280);
            assertThat(settlement.pointsRedeemed()).isZero();
            assertThat(settlement.charges().gross()).isEqualTo(280);
        }

        @Test
        @DisplayName("the four buckets stay gross — a points discount is recorded, not netted off")
        void bucketsStayGross() {
            Charges charges = new Charges(160, 120, 60, 0);

            Settlement settlement = Settlement.of(charges, RIFAT, 100, List.of(cash(240)));

            assertThat(settlement.charges().gaming()).isEqualTo(160);
            assertThat(settlement.charges().fnb()).isEqualTo(120);
            assertThat(settlement.charges().tournament()).isEqualTo(60);
            assertThat(settlement.pointsRedeemed()).isEqualTo(100);
            assertThat(settlement.totalDue()).isEqualTo(240);
        }

        @Test
        @DisplayName("a bill covered entirely by points takes no tenders at all")
        void pointsCanCoverEverything() {
            Settlement settlement = Settlement.of(Charges.fnb(120), RIFAT, 120, List.of());

            assertThat(settlement.pointsRedeemed()).isEqualTo(120);
            assertThat(settlement.totalDue()).isZero();
            assertThat(settlement.tenders()).isEmpty();
        }
    }

    @Nested
    @DisplayName("points")
    class Points {

        @Test
        @DisplayName("redemption is capped at what is owed, never taking the bill negative")
        void cappedAtTheBill() {
            Settlement settlement = Settlement.of(Charges.fnb(80), RIFAT, 500, List.of());

            assertThat(settlement.pointsRedeemed()).isEqualTo(80);
            assertThat(settlement.totalDue()).isZero();
        }

        @Test
        @DisplayName("asking for more points than the member holds buys less discount, not more")
        void cappedAtTheBalance() {
            Bill.Member almostEmpty = new Bill.Member(7L, "Rifat Hasan", 30, 0);

            Settlement settlement = Settlement.of(Charges.gaming(240), almostEmpty, 200,
                    List.of(cash(210)));

            assertThat(settlement.pointsRedeemed()).isEqualTo(30);
            assertThat(settlement.totalDue()).isEqualTo(210);
        }

        @Test
        @DisplayName("earning is floor(due / 20) of what was actually paid, not of the gross")
        void earnedOnWhatWasPaid() {
            Settlement settlement = Settlement.of(Charges.gaming(400), RIFAT, 100, List.of(cash(300)));

            assertThat(settlement.totalDue()).isEqualTo(300);
            assertThat(settlement.pointsEarned()).isEqualTo(15);
        }

        @Test
        @DisplayName("a walk-in earns nothing — there is nobody to credit")
        void walkInEarnsNothing() {
            Settlement settlement = Settlement.of(Charges.gaming(400), WALK_IN, null, List.of(cash(400)));

            assertThat(settlement.pointsEarned()).isZero();
            assertThat(settlement.memberId()).isNull();
            assertThat(settlement.loyalty().isEmpty()).isTrue();
        }

        @Test
        @DisplayName("a walk-in cannot redeem points")
        void walkInCannotRedeem() {
            assertThatThrownBy(() -> Settlement.of(Charges.gaming(400), WALK_IN, 100, List.of(cash(300))))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(codeOf(thrown)).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
    }

    @Nested
    @DisplayName("tenders")
    class Tenders {

        @Test
        @DisplayName("tenders under the total are 409 SPLIT_MISMATCH with both figures")
        void shortPayment() {
            assertThatThrownBy(() -> Settlement.of(Charges.gaming(280), WALK_IN, null, List.of(cash(200))))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> {
                        assertThat(codeOf(thrown)).isEqualTo(ErrorCode.SPLIT_MISMATCH);
                        assertThat(((ApiException) thrown).details())
                                .containsEntry("expected", 280)
                                .containsEntry("provided", 200);
                    });
        }

        @Test
        @DisplayName("tenders over the total are the same refusal — a settle is not a tip jar")
        void overPayment() {
            assertThatThrownBy(() -> Settlement.of(Charges.gaming(280), WALK_IN, null, List.of(cash(300))))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(codeOf(thrown)).isEqualTo(ErrorCode.SPLIT_MISMATCH));
        }

        @Test
        @DisplayName("a split across methods is fine as long as it adds up")
        void splitAcrossMethods() {
            Settlement settlement = Settlement.of(Charges.gaming(280), RIFAT, null,
                    List.of(cash(100), bkash(80, "8XK21QW7"), wallet(100)));

            assertThat(settlement.totalDue()).isEqualTo(280);
            assertThat(settlement.walletSpent()).isEqualTo(100);
            assertThat(settlement.tenders()).hasSize(3);
        }

        @Test
        @DisplayName("bKash without a TrxID is 409 PAYMENT_REF_REQUIRED")
        void bkashNeedsAReference() {
            assertThatThrownBy(() -> Settlement.of(Charges.gaming(80), WALK_IN, null,
                    List.of(bkash(80, "  "))))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown ->
                            assertThat(codeOf(thrown)).isEqualTo(ErrorCode.PAYMENT_REF_REQUIRED));
        }

        @Test
        @DisplayName("cash needs no reference")
        void cashNeedsNoReference() {
            assertThat(Settlement.of(Charges.gaming(80), WALK_IN, null, List.of(cash(80))).totalDue())
                    .isEqualTo(80);
        }

        @Test
        @DisplayName("spending past the wallet is 409 WALLET_INSUFFICIENT")
        void walletFloor() {
            assertThatThrownBy(() -> Settlement.of(Charges.gaming(600), RIFAT, null,
                    List.of(wallet(500), cash(100))))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown ->
                            assertThat(codeOf(thrown)).isEqualTo(ErrorCode.WALLET_INSUFFICIENT));
        }

        @Test
        @DisplayName("a walk-in has no wallet to spend from")
        void walkInHasNoWallet() {
            assertThatThrownBy(() -> Settlement.of(Charges.gaming(80), WALK_IN, null,
                    List.of(wallet(80))))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(codeOf(thrown)).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }

        @Test
        @DisplayName("a settle takes money — a zero or negative tender is a bad request")
        void tendersArePositive() {
            assertThatThrownBy(() -> Settlement.of(Charges.gaming(80), WALK_IN, null,
                    List.of(cash(100), cash(-20))))
                    .isInstanceOf(ApiException.class)
                    .satisfies(thrown -> assertThat(codeOf(thrown)).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
    }

    @Nested
    @DisplayName("the loyalty movement handed to the member package")
    class Loyalty {

        @Test
        @DisplayName("carries all three magnitudes, unsigned — the direction is the method's")
        void carriesEveryMovement() {
            Settlement settlement = Settlement.of(Charges.gaming(400), RIFAT, 100,
                    List.of(wallet(200), cash(100)));

            assertThat(settlement.loyalty().pointsRedeemed()).isEqualTo(100);
            assertThat(settlement.loyalty().pointsEarned()).isEqualTo(15);
            assertThat(settlement.loyalty().walletSpent()).isEqualTo(200);
        }
    }
}
