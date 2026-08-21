package dev.gamersden.billing.domain;

import dev.gamersden.common.spi.CartLookup;
import dev.gamersden.common.spi.MemberPointsLookup;
import dev.gamersden.common.spi.SessionBillLookup.BillableSession;
import dev.gamersden.common.spi.SessionBillLookup.TimeBlock;
import dev.gamersden.common.spi.TournamentBillLookup.TournamentCharge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bill panel's contract (api-contract.md, {@code GET /sessions/{id}/bill}), tested where it
 * actually lives: pure arithmetic over the four lookups, no database in sight.
 *
 * <p>The two cases worth the most money are the ones about {@code paid_tx_id}. A block that
 * carries one has been paid for exactly once — prepaid at a booking or play-ticket sale, or
 * settled mid-session while the clock kept running — and must never appear as due again
 * (invariant §5.9). Every other figure hangs off that: {@code netTotal}, the end guard, and the
 * points ceiling the settle will enforce.
 */
class BillTest {

    private static final long SEAT = 77L;
    private static final long STATION = 3L;
    private static final long PREPAID_SALE_TX = 4_207L;
    private static final long MID_SESSION_TX = 9_001L;
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-08-22T19:30:00+06:00");

    /** PS5 half-hour off the seeded rate card, and its morning-window (−25%) price. */
    private static final int PS5_HALF_HOUR = 80;
    private static final int PS5_MORNING = 60;

    // ---- empty bill -------------------------------------------------------------------------

    @Nested
    @DisplayName("an empty bill")
    class EmptyBill {

        @Test
        @DisplayName("a seat with no time, no cart and no member owes nothing at all")
        void nothingOnTheSeat() {
            Bill bill = bill(session());

            assertThat(bill.lines()).isEmpty();
            assertThat(bill.gamingDue()).isZero();
            assertThat(bill.fnbDue()).isZero();
            assertThat(bill.tournamentDue()).isZero();
            assertThat(bill.netTotal()).isZero();
            assertThat(bill.billableBlocks()).isZero();
            assertThat(bill.prepaidBlocks()).isZero();
            assertThat(bill.prepaidCredit()).isZero();
            assertThat(bill.pointsRedeemable()).isZero();
            assertThat(bill.isSettled()).isTrue();
        }

        @Test
        @DisplayName("an empty bill still carries the seat identity and the server clock")
        void stillIdentifiesTheSeat() {
            Bill bill = bill(session());

            assertThat(bill.sessionId()).isEqualTo(SEAT);
            assertThat(bill.stationId()).isEqualTo(STATION);
            assertThat(bill.sessionState()).isEqualTo("OPEN");
            assertThat(bill.serverTime()).isEqualTo(AT);
        }

        @Test
        @DisplayName("a walk-in has no member, so nothing is redeemable however big the bill")
        void noMemberNoRedemption() {
            Bill bill = bill(session(unpaid(PS5_HALF_HOUR), unpaid(PS5_HALF_HOUR)));

            assertThat(bill.member().attached()).isFalse();
            assertThat(bill.member().points()).isZero();
            assertThat(bill.netTotal()).isEqualTo(160);
            assertThat(bill.pointsRedeemable()).isZero();
        }

        @Test
        @DisplayName("an empty cart adds no lines and no total")
        void emptyCart() {
            Bill bill = Bill.of(session(), new CartLookup.UnsettledCart(12L, List.of()),
                    List.of(), null);

            assertThat(bill.lines()).isEmpty();
            assertThat(bill.fnbDue()).isZero();
        }
    }

    // ---- gaming: unbilled blocks only -------------------------------------------------------

    @Nested
    @DisplayName("gaming charges unbilled blocks only")
    class Gaming {

        @Test
        @DisplayName("every block is due while none of them has been paid for")
        void allUnpaid() {
            Bill bill = bill(session(unpaid(PS5_HALF_HOUR), unpaid(PS5_HALF_HOUR),
                    unpaid(PS5_HALF_HOUR)));

            assertThat(bill.gamingDue()).isEqualTo(240);
            assertThat(bill.billableBlocks()).isEqualTo(3);
            assertThat(bill.netTotal()).isEqualTo(240);
            assertThat(bill.lines()).singleElement().satisfies(line -> {
                assertThat(line.kind()).isEqualTo(BillLineKind.GAMING);
                assertThat(line.qty()).isEqualTo(3);
                assertThat(line.unitPrice()).isEqualTo(PS5_HALF_HOUR);
                assertThat(line.amount()).isEqualTo(240);
            });
        }

        @Test
        @DisplayName("after a mid-session settle only the blocks bought since are billed again")
        void afterAMidSessionSettle() {
            // Two blocks settled at 19:00, the session kept running and bought a third.
            Bill bill = bill(session(paid(PS5_HALF_HOUR, MID_SESSION_TX),
                    paid(PS5_HALF_HOUR, MID_SESSION_TX),
                    unpaid(PS5_HALF_HOUR)));

            assertThat(bill.gamingDue()).isEqualTo(80);
            assertThat(bill.billableBlocks()).isEqualTo(1);
            assertThat(bill.netTotal()).isEqualTo(80);
            assertThat(bill.lines()).singleElement()
                    .satisfies(line -> assertThat(line.qty()).isEqualTo(1));
        }

        @Test
        @DisplayName("a fully settled seat that bought no more time owes nothing and can end")
        void settledInFull() {
            Bill bill = bill(session(paid(PS5_HALF_HOUR, MID_SESSION_TX),
                    paid(PS5_HALF_HOUR, MID_SESSION_TX)));

            assertThat(bill.lines()).isEmpty();
            assertThat(bill.gamingDue()).isZero();
            assertThat(bill.netTotal()).isZero();
            assertThat(bill.isSettled()).isTrue();
        }

        @Test
        @DisplayName("blocks bought either side of the morning boundary bill at their own rates")
        void snapshotRatesAreNeverBlended() {
            // Two blocks at the morning rate, then two after 14:00 at the full rate.
            Bill bill = bill(session(unpaid(PS5_MORNING), unpaid(PS5_MORNING),
                    unpaid(PS5_HALF_HOUR), unpaid(PS5_HALF_HOUR)));

            assertThat(bill.gamingDue()).isEqualTo(280);
            assertThat(bill.lines()).hasSize(2);
            assertThat(bill.lines().get(0).unitPrice()).isEqualTo(PS5_MORNING);
            assertThat(bill.lines().get(0).qty()).isEqualTo(2);
            assertThat(bill.lines().get(1).unitPrice()).isEqualTo(PS5_HALF_HOUR);
            assertThat(bill.lines().get(1).qty()).isEqualTo(2);
        }

        @Test
        @DisplayName("a gaming line names no row — it is a group of blocks, not one of them")
        void gamingLineHasNoRef() {
            Bill bill = bill(session(unpaid(PS5_HALF_HOUR)));

            assertThat(bill.lines()).singleElement()
                    .satisfies(line -> assertThat(line.refId()).isNull());
        }
    }

    // ---- prepaid credit ---------------------------------------------------------------------

    @Nested
    @DisplayName("prepaid blocks are excluded from what is due")
    class Prepaid {

        @Test
        @DisplayName("a two-hour prepaid seat owes nothing — the booking sale already took it")
        void prepaidSeatOwesNothing() {
            Bill bill = bill(session(prepaid(PS5_HALF_HOUR), prepaid(PS5_HALF_HOUR),
                    prepaid(PS5_HALF_HOUR), prepaid(PS5_HALF_HOUR)));

            assertThat(bill.gamingDue()).isZero();
            assertThat(bill.netTotal()).isZero();
            assertThat(bill.isSettled()).isTrue();
            assertThat(bill.prepaidBlocks()).isEqualTo(4);
            assertThat(bill.prepaidCredit()).isEqualTo(320);
            assertThat(bill.billableBlocks()).isZero();
            assertThat(bill.lines()).isEmpty();
        }

        @Test
        @DisplayName("extra time on a prepaid seat is ordinary billable time")
        void extraTimeIsBillable() {
            Bill bill = bill(session(prepaid(PS5_HALF_HOUR), prepaid(PS5_HALF_HOUR),
                    prepaid(PS5_HALF_HOUR), prepaid(PS5_HALF_HOUR),
                    unpaid(PS5_HALF_HOUR)));

            assertThat(bill.gamingDue()).isEqualTo(80);
            assertThat(bill.netTotal()).isEqualTo(80);
            assertThat(bill.prepaidCredit()).isEqualTo(320);
            assertThat(bill.billableBlocks()).isEqualTo(1);
            assertThat(bill.prepaidBlocks()).isEqualTo(4);
        }

        @Test
        @DisplayName("the credit is never subtracted from netTotal — it was already excluded")
        void creditIsNotADiscount() {
            Bill bill = bill(session(prepaid(PS5_HALF_HOUR), prepaid(PS5_HALF_HOUR),
                    unpaid(PS5_HALF_HOUR)));

            // Subtracting the credit again would hand back 160 nobody ever charged.
            assertThat(bill.netTotal()).isEqualTo(80);
            assertThat(bill.netTotal() - bill.prepaidCredit()).isEqualTo(-80);
            assertThat(bill.gamingValue()).isEqualTo(240);
        }

        @Test
        @DisplayName("the credit reports what was covered, whichever transaction covered it")
        void prepaidAndMidSessionAreOneCredit() {
            Bill bill = bill(session(prepaid(PS5_HALF_HOUR), paid(PS5_HALF_HOUR, MID_SESSION_TX)));

            assertThat(bill.prepaidBlocks()).isEqualTo(2);
            assertThat(bill.prepaidCredit()).isEqualTo(160);
            assertThat(bill.netTotal()).isZero();
        }

        @Test
        @DisplayName("a prepaid seat with an open cart still owes the food")
        void prepaidTimeDoesNotCoverFood() {
            Bill bill = Bill.of(session(prepaid(PS5_HALF_HOUR), prepaid(PS5_HALF_HOUR)),
                    cart(line(1L, "Pepsi", 60, 2)), List.of(), null);

            assertThat(bill.gamingDue()).isZero();
            assertThat(bill.fnbDue()).isEqualTo(120);
            assertThat(bill.netTotal()).isEqualTo(120);
            assertThat(bill.isSettled()).isFalse();
        }
    }

    // ---- F&B --------------------------------------------------------------------------------

    @Nested
    @DisplayName("F&B comes from the unsettled cart")
    class FoodAndBeverage {

        @Test
        @DisplayName("each line is qty x snapshot price, and they add up to fnbDue")
        void lineMath() {
            Bill bill = Bill.of(session(),
                    cart(line(1L, "Pepsi 250ml", 60, 3), line(2L, "Chicken wrap", 220, 2)),
                    List.of(), null);

            assertThat(bill.lines()).hasSize(2);
            assertThat(bill.lines().get(0).amount()).isEqualTo(180);
            assertThat(bill.lines().get(1).amount()).isEqualTo(440);
            assertThat(bill.fnbDue()).isEqualTo(620);
            assertThat(bill.netTotal()).isEqualTo(620);
        }

        @Test
        @DisplayName("an F&B line names its item so the panel can step the qty")
        void lineNamesItsItem() {
            Bill bill = Bill.of(session(), cart(line(42L, "Pepsi 250ml", 60, 1)), List.of(), null);

            assertThat(bill.lines()).singleElement().satisfies(line -> {
                assertThat(line.kind()).isEqualTo(BillLineKind.FNB);
                assertThat(line.refId()).isEqualTo(42L);
                assertThat(line.label()).isEqualTo("Pepsi 250ml");
                assertThat(line.unitPrice()).isEqualTo(60);
            });
        }

        @Test
        @DisplayName("a settled cart is gone from the bill — the door hands back nothing")
        void settledCartIsNotOnTheBill() {
            // CartLookup filters settled carts out, so billing sees null and charges only gaming.
            Bill bill = Bill.of(session(unpaid(PS5_HALF_HOUR)), null, List.of(), null);

            assertThat(bill.fnbDue()).isZero();
            assertThat(bill.netTotal()).isEqualTo(80);
        }

        @Test
        @DisplayName("gaming and F&B sit on one bill, gaming first")
        void gamingThenFood() {
            Bill bill = Bill.of(session(unpaid(PS5_HALF_HOUR), unpaid(PS5_HALF_HOUR)),
                    cart(line(1L, "Pepsi", 60, 1)), List.of(), null);

            assertThat(bill.lines()).extracting(BillLine::kind)
                    .containsExactly(BillLineKind.GAMING, BillLineKind.FNB);
            assertThat(bill.gamingDue()).isEqualTo(160);
            assertThat(bill.fnbDue()).isEqualTo(60);
            assertThat(bill.netTotal()).isEqualTo(220);
        }
    }

    // ---- tournament placeholder -------------------------------------------------------------

    @Nested
    @DisplayName("tournament entry fees")
    class Tournaments {

        @Test
        @DisplayName("nothing is registered before B12, so the section is empty and costs nothing")
        void emptyUntilB12() {
            Bill bill = bill(session(unpaid(PS5_HALF_HOUR)));

            assertThat(bill.tournamentDue()).isZero();
            assertThat(bill.lines()).noneMatch(line -> line.kind() == BillLineKind.TOURNAMENT);
        }

        @Test
        @DisplayName("an entry fee bills at 1 x fee and joins netTotal once B12 fills the door")
        void entryFeeJoinsTheTotal() {
            Bill bill = Bill.of(session(unpaid(PS5_HALF_HOUR)), null,
                    List.of(new TournamentCharge(5L, 2L, "FIFA Friday", "Rifat", 300)), null);

            assertThat(bill.tournamentDue()).isEqualTo(300);
            assertThat(bill.netTotal()).isEqualTo(380);
            assertThat(bill.lines()).filteredOn(line -> line.kind() == BillLineKind.TOURNAMENT)
                    .singleElement()
                    .satisfies(line -> {
                        assertThat(line.refId()).isEqualTo(5L);
                        assertThat(line.label()).isEqualTo("FIFA Friday · Rifat");
                        assertThat(line.qty()).isEqualTo(1);
                        assertThat(line.amount()).isEqualTo(300);
                    });
        }

        @Test
        @DisplayName("an entry sold without a player name is labelled by its tournament alone")
        void anonymousEntry() {
            Bill bill = Bill.of(session(), null,
                    List.of(new TournamentCharge(5L, 2L, "FIFA Friday", null, 300)), null);

            assertThat(bill.lines()).singleElement()
                    .satisfies(line -> assertThat(line.label()).isEqualTo("FIFA Friday"));
        }
    }

    // ---- points cap -------------------------------------------------------------------------

    @Nested
    @DisplayName("pointsRedeemable is min(points, netTotal)")
    class PointsCap {

        @Test
        @DisplayName("a member with fewer points than the bill can redeem all of them")
        void pointsBelowTheBill() {
            Bill bill = Bill.of(session(unpaid(PS5_HALF_HOUR), unpaid(PS5_HALF_HOUR)), null,
                    List.of(), member(120));

            assertThat(bill.netTotal()).isEqualTo(160);
            assertThat(bill.pointsRedeemable()).isEqualTo(120);
        }

        @Test
        @DisplayName("a big balance is capped at the bill — points never become change")
        void pointsAboveTheBill() {
            Bill bill = Bill.of(session(unpaid(PS5_HALF_HOUR)), null, List.of(), member(5_000));

            assertThat(bill.netTotal()).isEqualTo(80);
            assertThat(bill.pointsRedeemable()).isEqualTo(80);
        }

        @Test
        @DisplayName("points and bill equal: the whole balance covers the whole bill")
        void exactlyEqual() {
            Bill bill = Bill.of(session(unpaid(PS5_HALF_HOUR)), null, List.of(), member(80));

            assertThat(bill.pointsRedeemable()).isEqualTo(80);
        }

        @Test
        @DisplayName("the cap follows netTotal, so prepaid time never inflates it")
        void capIsAgainstWhatIsDueNotWhatWasPlayed() {
            // Four prepaid blocks plus one billable: only the 80 still owed can be redeemed against.
            Bill bill = Bill.of(session(prepaid(PS5_HALF_HOUR), prepaid(PS5_HALF_HOUR),
                            prepaid(PS5_HALF_HOUR), prepaid(PS5_HALF_HOUR), unpaid(PS5_HALF_HOUR)),
                    null, List.of(), member(500));

            assertThat(bill.gamingValue()).isEqualTo(400);
            assertThat(bill.pointsRedeemable()).isEqualTo(80);
        }

        @Test
        @DisplayName("nothing due, nothing to redeem — even on a member with points to spare")
        void nothingDue() {
            Bill bill = Bill.of(session(prepaid(PS5_HALF_HOUR)), null, List.of(), member(900));

            assertThat(bill.netTotal()).isZero();
            assertThat(bill.pointsRedeemable()).isZero();
        }

        @Test
        @DisplayName("a member with an empty points balance redeems nothing")
        void noPoints() {
            Bill bill = Bill.of(session(unpaid(PS5_HALF_HOUR)), null, List.of(), member(0));

            assertThat(bill.member().attached()).isTrue();
            assertThat(bill.pointsRedeemable()).isZero();
        }

        @Test
        @DisplayName("the member rides along with their wallet for the split-payment panel")
        void memberTravelsWithTheBill() {
            Bill bill = Bill.of(session(), null, List.of(), member(318));

            assertThat(bill.member().id()).isEqualTo(11L);
            assertThat(bill.member().name()).isEqualTo("Rifat Hasan");
            assertThat(bill.member().points()).isEqualTo(318);
            assertThat(bill.member().wallet()).isEqualTo(1_240);
        }
    }

    // ---- the arithmetic contract ------------------------------------------------------------

    @Nested
    @DisplayName("the contract the FE panel relies on")
    class Contract {

        @Test
        @DisplayName("the lines always add up to netTotal, prepaid credit sitting outside it")
        void linesSumToNetTotal() {
            Bill bill = Bill.of(
                    session(prepaid(PS5_HALF_HOUR), prepaid(PS5_HALF_HOUR),
                            unpaid(PS5_MORNING), unpaid(PS5_HALF_HOUR)),
                    cart(line(1L, "Pepsi", 60, 2), line(2L, "Wrap", 220, 1)),
                    List.of(new TournamentCharge(5L, 2L, "FIFA Friday", "Rifat", 300)),
                    member(1_000));

            int summed = bill.lines().stream().mapToInt(BillLine::amount).sum();
            assertThat(summed).isEqualTo(bill.netTotal());
            assertThat(bill.netTotal())
                    .isEqualTo(bill.gamingDue() + bill.fnbDue() + bill.tournamentDue())
                    .isEqualTo(140 + 340 + 300);
            assertThat(bill.prepaidCredit()).isEqualTo(160);
            assertThat(bill.pointsRedeemable()).isEqualTo(780);
        }

        @Test
        @DisplayName("gamingValue is what the seat's time is worth, billed or not")
        void gamingValueCoversBothHalves() {
            Bill bill = bill(session(prepaid(PS5_HALF_HOUR), unpaid(PS5_MORNING)));

            assertThat(bill.gamingValue()).isEqualTo(bill.gamingDue() + bill.prepaidCredit())
                    .isEqualTo(140);
        }

        @Test
        @DisplayName("a closed seat still reads, it just has nothing left to charge")
        void closedSeatStillReads() {
            Bill bill = Bill.of(new BillableSession(SEAT, STATION, null, "CLOSED",
                    List.of(paid(PS5_HALF_HOUR, MID_SESSION_TX)), AT), null, List.of(), null);

            assertThat(bill.sessionState()).isEqualTo("CLOSED");
            assertThat(bill.netTotal()).isZero();
            assertThat(bill.isSettled()).isTrue();
        }
    }

    // ---- fixtures ---------------------------------------------------------------------------

    private static Bill bill(BillableSession session) {
        return Bill.of(session, null, List.of(), null);
    }

    private static BillableSession session(TimeBlock... blocks) {
        return new BillableSession(SEAT, STATION, null, "OPEN", Arrays.asList(blocks), AT);
    }

    private static TimeBlock unpaid(int price) {
        return new TimeBlock(price, false);
    }

    /** A block settled mid-session — {@code paid_tx_id} points at this session's own payment. */
    private static TimeBlock paid(int price, long txId) {
        assertThat(txId).isPositive();
        return new TimeBlock(price, true);
    }

    /** A block born paid at a booking or play-ticket sale (invariant §5.9). */
    private static TimeBlock prepaid(int price) {
        return paid(price, PREPAID_SALE_TX);
    }

    private static CartLookup.UnsettledCart cart(CartLookup.Line... lines) {
        return new CartLookup.UnsettledCart(12L, Arrays.asList(lines));
    }

    private static CartLookup.Line line(long itemId, String name, int unitPrice, int qty) {
        return new CartLookup.Line(itemId, name, "BEVERAGE", qty, unitPrice);
    }

    private static MemberPointsLookup.Loyalty member(int points) {
        return new MemberPointsLookup.Loyalty(11L, "Rifat Hasan", points, 1_240);
    }
}
