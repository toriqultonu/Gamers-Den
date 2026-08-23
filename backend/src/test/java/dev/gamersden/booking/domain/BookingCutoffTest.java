package dev.gamersden.booking.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The arithmetic on a {@link Booking} row, with no database and no clock bean in the way — the
 * cutoff window, the derived total and the overlap warning.
 *
 * <p>All three are computed at read time from the booking's own snapshots and never stored
 * (invariants §5.4, §5.11), which is exactly what makes them unit-testable: everything they need
 * is on the row.
 */
class BookingCutoffTest {

    private static final OffsetDateTime START =
            OffsetDateTime.of(2026, 8, 24, 18, 0, 0, 0, ZoneOffset.ofHours(6));

    @Nested
    @DisplayName("the cancellation window")
    class CancellationWindow {

        @Test
        @DisplayName("closes exactly cutoff hours before the slot")
        void deadlineIsStartMinusCutoff() {
            assertThat(booking(2).cancellableUntil()).isEqualTo(START.minusHours(2));
        }

        @Test
        @DisplayName("the boundary itself is still inside the window")
        void boundaryIsInclusive() {
            Booking booking = booking(2);

            assertThat(booking.cancellableAt(START.minusHours(2))).isTrue();
            assertThat(booking.cancellableAt(START.minusHours(2).minusSeconds(1))).isTrue();
            assertThat(booking.cancellableAt(START.minusHours(2).plusSeconds(1))).isFalse();
        }

        @Test
        @DisplayName("a zero-hour cutoff locks only once the slot has started")
        void zeroCutoff() {
            Booking booking = booking(0);

            assertThat(booking.cancellableAt(START.minusSeconds(1))).isTrue();
            assertThat(booking.cancellableAt(START)).isTrue();
            assertThat(booking.cancellableAt(START.plusSeconds(1))).isFalse();
        }

        @Test
        @DisplayName("only a PAID booking is cancellable, whatever the clock says")
        void statusGatesTheWindow() {
            Booking booking = booking(2);
            booking.setStatus(BookingStatus.ARRIVED);

            assertThat(booking.cancellableAt(START.minusDays(1))).isFalse();
        }
    }

    @Nested
    @DisplayName("derived figures")
    class Derived {

        @Test
        @DisplayName("the total is play time plus the package fee, both snapshots")
        void total() {
            assertThat(booking(2).total()).isEqualTo(240 + 100);
        }

        @Test
        @DisplayName("the slot runs 30 minutes per prepaid block")
        void endAt() {
            assertThat(booking(2).endAt()).isEqualTo(START.plusMinutes(90));
        }
    }

    @Nested
    @DisplayName("the overlap warning")
    class Overlap {

        @Test
        @DisplayName("two slots on one console that share any minute overlap")
        void sharedMinutes() {
            Booking first = booking(2);
            Booking later = bookingAt(START.plusMinutes(60), 2, 1L);

            assertThat(first.overlaps(later)).isTrue();
            assertThat(later.overlaps(first)).isTrue();
        }

        @Test
        @DisplayName("back-to-back slots do not — the end is exclusive")
        void backToBack() {
            Booking first = booking(2);
            Booking next = bookingAt(START.plusMinutes(90), 2, 1L);

            assertThat(first.overlaps(next)).isFalse();
            assertThat(next.overlaps(first)).isFalse();
        }

        @Test
        @DisplayName("the same time on a different console is not a clash")
        void differentConsole() {
            assertThat(booking(2).overlaps(bookingAt(START, 2, 2L))).isFalse();
        }
    }

    /** 3 blocks at 80, a 100 package fee, on station 1. */
    private static Booking booking(int cutoffHours) {
        return bookingWithCutoff(START, 3, 1L, cutoffHours);
    }

    private static Booking bookingAt(OffsetDateTime startAt, int blocks, long stationId) {
        return bookingWithCutoff(startAt, blocks, stationId, 2);
    }

    private static Booking bookingWithCutoff(OffsetDateTime startAt, int blocks, long stationId,
                                             int cutoffHours) {
        return new Booking(stationId, null, "Rifat Hasan", null, startAt, blocks, blocks * 80, 100,
                cutoffHours, 1L);
    }
}
