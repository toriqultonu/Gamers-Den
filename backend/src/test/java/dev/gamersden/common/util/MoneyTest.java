package dev.gamersden.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void pointsEarnedIsFloorOfDueOverTwenty() {
        assertThat(Money.pointsEarned(0)).isZero();
        assertThat(Money.pointsEarned(19)).isZero();
        assertThat(Money.pointsEarned(20)).isEqualTo(1);
        assertThat(Money.pointsEarned(259)).isEqualTo(12);
        assertThat(Money.pointsEarned(-50)).isZero();
    }

    @Test
    void redemptionIsCappedAtTheBillTotal() {
        assertThat(Money.cappedRedemption(500, 320)).isEqualTo(320);
        assertThat(Money.cappedRedemption(120, 320)).isEqualTo(120);
        assertThat(Money.cappedRedemption(-5, 320)).isZero();
    }

    @Test
    void percentDiscountStaysInIntegerBdt() {
        assertThat(Money.applyPercentDiscount(200, 25)).isEqualTo(150);
        assertThat(Money.applyPercentDiscount(150, 25)).isEqualTo(113);
        assertThat(Money.applyPercentDiscount(200, 0)).isEqualTo(200);
        assertThat(Money.applyPercentDiscount(200, 100)).isZero();
    }

    @Test
    void percentDiscountRejectsOutOfRangePercent() {
        assertThatThrownBy(() -> Money.applyPercentDiscount(200, 101))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
