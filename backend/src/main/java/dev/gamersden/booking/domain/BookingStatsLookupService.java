package dev.gamersden.booking.domain;

import dev.gamersden.booking.repo.BookingRepository;
import dev.gamersden.common.config.VenueTime;
import dev.gamersden.common.spi.BookingStatsLookup;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The {@code booking} package's answer to {@link BookingStatsLookup} — the only door
 * {@code report} uses into {@code bookings} (ARCHITECTURE.md §3).
 *
 * <p>{@link #preSold()} reads the live table rather than a window, because S2's pre-sold stat is
 * about money the venue is holding right now: every booking still in PAID owes a seat, whenever
 * it was sold.
 */
@Service
public class BookingStatsLookupService implements BookingStatsLookup {

    private final BookingRepository bookings;
    private final Clock clock;

    public BookingStatsLookupService(BookingRepository bookings, Clock clock) {
        this.bookings = bookings;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DailyBookings> dailyBookings(OffsetDateTime from, OffsetDateTime to,
                                             OffsetDateTime now) {
        OffsetDateTime cutoff = now == null ? VenueTime.now(clock) : now;
        return bookings.dailyAttendance(from, to, cutoff).stream()
                .map(row -> new DailyBookings(row.getDay(), row.getBooked(), row.getUsed(),
                        row.getCancelled(), row.getArrived(), row.getExpired()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public BookingIncome income(OffsetDateTime from, OffsetDateTime to) {
        BookingRepository.BookingMoneyRow row = bookings.incomeBetween(from, to);
        return new BookingIncome(row.getBookings(), row.getPlayAmount(), row.getPackageFee());
    }

    @Override
    @Transactional(readOnly = true)
    public PreSoldBookings preSold() {
        BookingRepository.BookingMoneyRow row = bookings.preSold();
        return new PreSoldBookings(row.getBookings(), row.getPlayAmount(), row.getPackageFee());
    }
}
