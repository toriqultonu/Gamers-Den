package dev.gamersden.common.spi;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The narrow read the {@code report} package needs from {@code booking} — the three figures
 * docs/bookings.md §6 asks reports for (bookings per day, show-rate, package-fee income) and the
 * booking half of S2's pre-sold stat — without reaching for {@code BookingRepository}
 * (ARCHITECTURE.md §3).
 *
 * <p>Two different dates are in play and they are not interchangeable. Attendance — per-day counts
 * and the show-rate — is keyed on {@code start_at}, the slot the customer was expected in.
 * Income is keyed on {@code created_at}, the day the money was taken, so it lines up with the
 * transactions that carry it. A booking sold on Monday for Tuesday counts once in each, on
 * different days, on purpose.
 */
public interface BookingStatsLookup {

    /**
     * Attendance for slots starting inside the window, oldest day first; days with no slots are
     * absent.
     *
     * @param now what "expired" is measured against — a slot already started that was never
     *            checked in. Server time, never the client's (invariant §5.1)
     */
    List<DailyBookings> dailyBookings(OffsetDateTime from, OffsetDateTime to, OffsetDateTime now);

    /** What bookings sold in the window brought in, cancelled ones (fully refunded) excluded. */
    BookingIncome income(OffsetDateTime from, OffsetDateTime to);

    /** Bookings still PAID — paid for, not yet checked in. The booking half of S2's pre-sold stat. */
    PreSoldBookings preSold();

    /**
     * One day's slots.
     *
     * @param booked  slots that were due to start that day, whatever became of them
     * @param used    seated from the Floor — the show-rate's numerator
     * @param arrived checked in but not yet seated; counted in neither half of the show-rate,
     *                because they turned up and the visit has not finished either way
     * @param expired still PAID with the slot already past — a no-show
     */
    record DailyBookings(LocalDate day,
                         int booked,
                         int used,
                         int cancelled,
                         int arrived,
                         int expired) {
    }

    /** @param packageFee the flat per-booking fee docs/bookings.md §1 adds on top of play time */
    record BookingIncome(int bookings, int playAmount, int packageFee) {

        public int total() {
            return playAmount + packageFee;
        }
    }

    record PreSoldBookings(int bookings, int playAmount, int packageFee) {

        public int amount() {
            return playAmount + packageFee;
        }
    }
}
