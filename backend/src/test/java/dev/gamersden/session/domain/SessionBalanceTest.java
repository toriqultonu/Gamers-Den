package dev.gamersden.session.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The net-outstanding end guard (invariant §5.9, docs/backend-architecture.md §6):
 * {@code sum(unpaid session_blocks) + unsettled cart}. Zero lets the seat close; anything above it
 * is 409 {@code SESSION_HAS_BALANCE}.
 *
 * <p>The case that matters most is the prepaid one: a booking or play-ticket seat was charged up
 * front, so its blocks are born carrying the sale's {@code paid_tx_id} and must never show up as
 * due — otherwise the customer would be asked to pay for the same two hours twice.
 */
class SessionBalanceTest {

    private static final long SALE_TX = 4_207L;

    @Test
    void anEmptySessionOwesNothing() {
        SessionBalance balance = SessionBalance.of(List.of(), 0);

        assertThat(balance.netOutstanding()).isZero();
        assertThat(balance.isSettled()).isTrue();
    }

    @Test
    void unpaidBlocksAreTheGamingHalf() {
        SessionBalance balance = SessionBalance.of(List.of(unpaid(80), unpaid(80), unpaid(60)), 0);

        assertThat(balance.gamingDue()).isEqualTo(220);
        assertThat(balance.fnbDue()).isZero();
        assertThat(balance.netOutstanding()).isEqualTo(220);
        assertThat(balance.isSettled()).isFalse();
    }

    @Test
    void blocksPaidMidSessionDropOutWhileTheRestStayDue() {
        // A settle happened after two blocks; the session kept running and bought a third.
        SessionBalance balance =
                SessionBalance.of(List.of(paid(80), paid(80), unpaid(80)), 0);

        assertThat(balance.gamingDue()).isEqualTo(80);
        assertThat(balance.isSettled()).isFalse();
    }

    @Test
    void prepaidBlocksCountAsSettledSoAPrepaidSeatEndsWithoutASecondPayment() {
        // Four blocks born paid by the booking sale — two hours prepaid, nothing owed.
        List<SessionBlock> prepaid =
                List.of(paid(80), paid(80), paid(80), paid(80));

        SessionBalance balance = SessionBalance.of(prepaid, 0);

        assertThat(balance.gamingDue()).isZero();
        assertThat(balance.netOutstanding()).isZero();
        assertThat(balance.isSettled()).isTrue();
    }

    @Test
    void extraTimeOnAPrepaidSeatIsOrdinaryBillableTime() {
        // Prepaid two hours, then +30 min at the counter rate: only the extra block is due.
        SessionBalance balance =
                SessionBalance.of(List.of(paid(80), paid(80), paid(80), paid(80), unpaid(80)), 0);

        assertThat(balance.gamingDue()).isEqualTo(80);
        assertThat(balance.isSettled()).isFalse();
    }

    @Test
    void anUnsettledCartAloneBlocksTheEnd() {
        SessionBalance balance = SessionBalance.of(List.of(paid(80)), 250);

        assertThat(balance.gamingDue()).isZero();
        assertThat(balance.fnbDue()).isEqualTo(250);
        assertThat(balance.netOutstanding()).isEqualTo(250);
        assertThat(balance.isSettled()).isFalse();
    }

    @Test
    void bothHalvesAddUp() {
        SessionBalance balance = SessionBalance.of(List.of(unpaid(80), unpaid(80)), 250);

        assertThat(balance.netOutstanding()).isEqualTo(410);
    }

    @Test
    void returnedBlocksAreNeverBilled() {
        SessionBlock returned = unpaid(80);
        returned.setRemoved(true);

        assertThat(SessionBalance.of(List.of(unpaid(80), returned), 0).gamingDue()).isEqualTo(80);
    }

    @Test
    void aRefundedCartCannotPushTheBalanceNegativeAndUnlockNothing() {
        // Defensive: a negative cart total would otherwise cancel out real unpaid gaming time.
        SessionBalance balance = SessionBalance.of(List.of(unpaid(80)), -500);

        assertThat(balance.fnbDue()).isZero();
        assertThat(balance.netOutstanding()).isEqualTo(80);
        assertThat(balance.isSettled()).isFalse();
    }

    @Test
    void zeroIsTheOnlySettledBalance() {
        assertThat(SessionBalance.ZERO.isSettled()).isTrue();
        assertThat(SessionBalance.of(List.of(unpaid(1)), 0).isSettled()).isFalse();
        assertThat(SessionBalance.of(List.of(), 1).isSettled()).isFalse();
    }

    private static SessionBlock unpaid(int price) {
        return new SessionBlock(1L, price, null);
    }

    private static SessionBlock paid(int price) {
        return new SessionBlock(1L, price, SALE_TX);
    }
}
