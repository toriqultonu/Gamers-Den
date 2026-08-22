package dev.gamersden.shift.domain;

import dev.gamersden.common.spi.ShiftTakingsLookup.PostedTransaction;
import dev.gamersden.common.spi.ShiftTakingsLookup.Tender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The X/Z arithmetic, without a database.
 *
 * <p>What has to hold, whatever the shift did:
 *
 * <ul>
 *   <li>every row's four categories sum to that row's total;</li>
 *   <li>the rows sum to the totals line, and the totals line's total is what was tendered;</li>
 *   <li>a sale and its reversal cancel out exactly, to the taka, in every cell.</li>
 * </ul>
 *
 * <p>The last one is why the attribution is largest-remainder rather than rounding: a ৳180 tender
 * against a ৳280 bill divides into thirds that do not land on whole taka, and a report that lost
 * one per receipt would have the drawer arguing with the till by closing time.
 */
class ShiftTakingsTest {

    private static final List<String> METHODS = List.of("CASH", "BKASH", "NAGAD", "WALLET");

    @Test
    @DisplayName("a shift that took nothing still reports a row per method")
    void emptyShift() {
        ShiftTakings takings = ShiftTakings.of(List.of(), METHODS);

        assertThat(takings.byMethod()).extracting(MethodTakings::method)
                .containsExactly("CASH", "BKASH", "NAGAD", "WALLET");
        assertThat(takings.byMethod()).allMatch(MethodTakings::isEmpty);
        assertThat(takings.totals().total()).isZero();
        assertThat(takings.cash()).isZero();
        assertThat(takings.saleCount()).isZero();
        assertThat(takings.refundCount()).isZero();
    }

    @Test
    @DisplayName("one cash sale lands whole in the cash row, split across what it bought")
    void oneCashSale() {
        ShiftTakings takings = ShiftTakings.of(
                List.of(sale(1, 160, 120, 0, 0, 0, 280, tender("CASH", 280))), METHODS);

        MethodTakings cash = row(takings, "CASH");
        assertThat(cash.gaming()).isEqualTo(160);
        assertThat(cash.fnb()).isEqualTo(120);
        assertThat(cash.total()).isEqualTo(280);
        assertThat(takings.cash()).isEqualTo(280);
        assertThat(takings.totals().total()).isEqualTo(280);
        assertThat(takings.saleCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a split payment attributes each tender to the categories in proportion")
    void splitPayment() {
        ShiftTakings takings = ShiftTakings.of(
                List.of(sale(1, 160, 120, 0, 0, 0, 280,
                        tender("CASH", 100), tender("BKASH", 180))), METHODS);

        // 100 of 280 is 57.1% gaming / 42.9% F&B; the leftover taka goes to F&B, which lost more
        // to truncation. 180 of 280 divides the other way.
        assertThat(row(takings, "CASH")).isEqualTo(new MethodTakings("CASH", 57, 43, 0, 0, 100));
        assertThat(row(takings, "BKASH")).isEqualTo(new MethodTakings("BKASH", 103, 77, 0, 0, 180));
        assertThat(takings.totals())
                .isEqualTo(new MethodTakings(MethodTakings.TOTAL, 160, 120, 0, 0, 280));
    }

    @Test
    @DisplayName("a points discount is absorbed pro rata, and reported on its own line")
    void pointsDiscount() {
        // 280 of gaming and F&B, 100 of it paid with points: 180 actually reaches the drawer.
        ShiftTakings takings = ShiftTakings.of(
                List.of(sale(1, 160, 120, 0, 0, 100, 180, tender("CASH", 180))), METHODS);

        MethodTakings cash = row(takings, "CASH");
        assertThat(cash.total()).isEqualTo(180);
        assertThat(cash.gaming() + cash.fnb()).isEqualTo(180);
        assertThat(takings.pointsRedeemed()).isEqualTo(100);
    }

    @Test
    @DisplayName("tournament and pre-booking lines are their own columns")
    void tournamentAndBookingColumns() {
        ShiftTakings takings = ShiftTakings.of(List.of(
                sale(1, 0, 0, 500, 0, 0, 500, tender("CASH", 500)),
                sale(2, 0, 0, 0, 640, 0, 640, tender("BKASH", 640))), METHODS);

        assertThat(row(takings, "CASH").tournament()).isEqualTo(500);
        assertThat(row(takings, "BKASH").booking()).isEqualTo(640);
        assertThat(takings.totals())
                .isEqualTo(new MethodTakings(MethodTakings.TOTAL, 0, 0, 500, 640, 1140));
    }

    @Nested
    @DisplayName("refunds")
    class Refunds {

        @Test
        @DisplayName("a reversal cancels its sale out in every cell")
        void reversalCancelsTheSale() {
            PostedTransaction sale = sale(1, 160, 120, 0, 0, 0, 280,
                    tender("CASH", 100), tender("BKASH", 180));
            PostedTransaction reversal = new PostedTransaction(2, "GD-0101-002", -160, -120, 0, 0,
                    0, 0, -280, false,
                    List.of(tender("CASH", -100), tender("BKASH", -180)));

            ShiftTakings takings = ShiftTakings.of(List.of(sale, reversal), METHODS);

            assertThat(takings.byMethod()).allMatch(MethodTakings::isEmpty);
            assertThat(takings.totals().total()).isZero();
            assertThat(takings.cash()).isZero();
            assertThat(takings.saleCount()).isEqualTo(1);
            assertThat(takings.refundCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("the voided sale itself still counts — it is the pair that nets to nothing")
        void theVoidedSaleIsNotDropped() {
            PostedTransaction voided = new PostedTransaction(1, "GD-0101-001", 160, 0, 0, 0, 0, 8,
                    160, true, List.of(tender("CASH", 160)));

            ShiftTakings takings = ShiftTakings.of(List.of(voided), METHODS);

            assertThat(takings.cash()).isEqualTo(160);
            assertThat(takings.saleCount()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a method nobody named still gets a row rather than vanishing")
    void unknownMethodIsNotSwallowed() {
        ShiftTakings takings = ShiftTakings.of(
                List.of(sale(1, 100, 0, 0, 0, 0, 100, tender("VOUCHER", 100))), METHODS);

        assertThat(takings.byMethod()).extracting(MethodTakings::method).contains("VOUCHER");
        assertThat(takings.totals().total()).isEqualTo(100);
    }

    @Test
    @DisplayName("every row adds up to its total, and the rows add up to the totals line")
    void theMatrixAlwaysReconciles() {
        ShiftTakings takings = ShiftTakings.of(List.of(
                sale(1, 160, 120, 0, 0, 100, 180, tender("CASH", 79), tender("WALLET", 101)),
                sale(2, 0, 55, 0, 0, 0, 55, tender("NAGAD", 55)),
                sale(3, 240, 35, 500, 0, 7, 768, tender("CASH", 383), tender("BKASH", 385))),
                METHODS);

        takings.byMethod().forEach(rowTotalsAddUp());
        rowTotalsAddUp().accept(takings.totals());
        assertThat(takings.byMethod().stream().mapToInt(MethodTakings::total).sum())
                .isEqualTo(takings.totals().total())
                .isEqualTo(180 + 55 + 768);
    }

    private static java.util.function.Consumer<MethodTakings> rowTotalsAddUp() {
        return row -> assertThat(row.gaming() + row.fnb() + row.tournament() + row.booking())
                .as("categories of %s", row.method())
                .isEqualTo(row.total());
    }

    private static MethodTakings row(ShiftTakings takings, String method) {
        return takings.byMethod().stream()
                .filter(candidate -> candidate.method().equals(method))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + method));
    }

    private static PostedTransaction sale(long id, int gaming, int fnb, int tournament, int booking,
                                          int pointsRedeemed, int totalDue, Tender... tenders) {
        return new PostedTransaction(id, "GD-0101-%03d".formatted(id), gaming, fnb, tournament,
                booking, pointsRedeemed, totalDue / 20, totalDue, false, List.of(tenders));
    }

    private static Tender tender(String method, int amount) {
        return new Tender(method, amount);
    }
}
