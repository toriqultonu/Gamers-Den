package dev.gamersden.station.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The block-price rule the whole money path snapshots: the morning window is half-open and the
 * discount lands on the half-hour rate, never on an already-sold block.
 *
 * <p>10:00-14:00 at -25% is the documented default for the morning-discount OPEN FLAG
 * (ARCHITECTURE.md) — unconfirmed by the venue, and editable through {@code PUT /pricing}.
 */
class PricingRatesTest {

    @Test
    void theMorningWindowIsHalfOpen() {
        Pricing ps5 = seededPs5();

        assertThat(ps5.isMorning(LocalTime.of(9, 59))).isFalse();
        assertThat(ps5.isMorning(LocalTime.of(10, 0))).isTrue();
        assertThat(ps5.isMorning(LocalTime.of(13, 59, 59))).isTrue();
        assertThat(ps5.isMorning(LocalTime.of(14, 0))).isFalse();
    }

    @Test
    void aMorningBlockIsDiscountedAndEveryOtherHourPaysTheFullRate() {
        Pricing ps5 = seededPs5();

        assertThat(ps5.blockPriceAt(LocalTime.of(11, 30))).isEqualTo(60);
        assertThat(ps5.blockPriceAt(LocalTime.of(20, 0))).isEqualTo(80);
    }

    @Test
    void aZeroPercentDiscountSwitchesTheMorningRateOffWithoutTouchingTheWindow() {
        Pricing ps5 = seededPs5();
        ps5.setMorningDiscountPct(0);

        assertThat(ps5.isMorning(LocalTime.of(11, 30))).isTrue();
        assertThat(ps5.blockPriceAt(LocalTime.of(11, 30))).isEqualTo(80);
    }

    @Test
    void theDiscountRoundsHalfUpOnIntegerBdt() {
        Pricing ps4 = new Pricing(ConsoleType.PS4, 80, 50);
        ps4.setMorningDiscountPct(25);

        // 50 - 25% = 37.5 -> 38 BDT; money is integer everywhere.
        assertThat(ps4.blockPriceAt(LocalTime.of(10, 30))).isEqualTo(38);
    }

    private static Pricing seededPs5() {
        return new Pricing(ConsoleType.PS5, 120, 80);
    }
}
