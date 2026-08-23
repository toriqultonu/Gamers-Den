package dev.gamersden.support;

import dev.gamersden.common.config.VenueTime;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;

/**
 * Four venue days of trading, written straight through JDBC.
 *
 * <p>A report is about history, and history is the one thing the API cannot make: every endpoint
 * writes {@code now()}. Driving four days of sales through {@code POST /payments} would need the
 * clock moved between each one and would still leave the rows stamped by the database's own
 * default. So the seed is SQL — the same columns the money path writes, put where a week of
 * trading would have left them.
 *
 * <p>Every figure the report suites assert is derivable from what follows, and the awkward rows are
 * the point: a refund, a void and its reversal, a bill part-paid in points, a console under
 * maintenance, a live session and an open till, and bookings in all five states.
 */
public final class ReportFixtures {

    /** A fixed Wednesday, so weekday folding and 30-day windows are the same on every run. */
    public static final LocalDate TODAY = LocalDate.of(2026, 5, 20);

    /** Late enough that every seeded posting is in the past, early enough that the till is open. */
    public static final LocalTime NOW = LocalTime.of(22, 0);

    private final JdbcTemplate jdbc;
    private final MutableClock clock;
    private final Long staffId;

    public Long ps4Station;
    public Long firstStation;
    public Long secondStation;
    public Long closedShiftA;
    public Long closedShiftB;
    public Long openShift;

    public ReportFixtures(JdbcTemplate jdbc, MutableClock clock, Long staffId) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.staffId = staffId;
    }

    /** The venue day {@code offset} days from the seeded "today" (negative is the past). */
    public LocalDate day(int offset) {
        return TODAY.plusDays(offset);
    }

    public OffsetDateTime at(int dayOffset, int hour) {
        return at(dayOffset, LocalTime.of(hour, 0));
    }

    public OffsetDateTime at(int dayOffset, LocalTime time) {
        return ZonedDateTime.of(day(dayOffset), time, VenueTime.ZONE).toOffsetDateTime();
    }

    public void write() {
        clock.setToVenueTime(TODAY, NOW);
        stations();
        shifts();
        money();
        floor();
        bookings();
    }

    // ---- the floor ---------------------------------------------------------------------------

    private void stations() {
        ps4Station = station("PS4-01", "PS4", "MAINTENANCE");
        firstStation = station("PS5-01", "PS5", "AVAILABLE");
        secondStation = station("PS5-02", "PS5", "AVAILABLE");
    }

    private Long station(String name, String consoleType, String status) {
        return jdbc.queryForObject(
                "INSERT INTO stations (name, console_type, status) VALUES (?, ?, ?) RETURNING id",
                Long.class, name, consoleType, status);
    }

    /**
     * Two closed tills and one still open. Trading hours are the union of these, and the open one
     * is what forces the report to clip at "now" instead of running to midnight.
     */
    private void shifts() {
        closedShiftA = closedShift(at(-2, 12), at(-2, 22), 2000, 5200, 5000, 200);
        closedShiftB = closedShift(at(-1, 12), at(-1, 20), 2000, 2400, 2500, -100);
        openShift = jdbc.queryForObject(
                "INSERT INTO shifts (staff_id, terminal, opening_float, opened_at) "
                        + "VALUES (?, 'T1', 3000, ?) RETURNING id",
                Long.class, staffId, at(0, 10));
    }

    private Long closedShift(OffsetDateTime openedAt, OffsetDateTime closedAt, int openingFloat,
                             int counted, int expected, int discrepancy) {
        return jdbc.queryForObject(
                "INSERT INTO shifts (staff_id, terminal, opening_float, opened_at, closed_at, "
                        + "counted_cash, expected_cash, discrepancy) "
                        + "VALUES (?, 'T1', ?, ?, ?, ?, ?, ?) RETURNING id",
                Long.class, staffId, openingFloat, openedAt, closedAt, counted, expected,
                discrepancy);
    }

    // ---- the money ---------------------------------------------------------------------------

    private Long pepsi;
    private Long chips;
    private Long water;

    /** Ids the booking rows hang off; assigned in {@link #money()} and read in {@link #bookings()}. */
    private Long saleTwoDaysAgo;
    private Long bookingSaleYesterday;
    private Long saleToday;
    private Long tournamentAndBookingToday;
    private Long refundToday;

    private void money() {
        pepsi = item("Pepsi 250ml", "BEVERAGE", 60, 4, 10);
        chips = item("Chips", "SNACK", 40, 50, 5);
        water = item("Water 500ml", "BEVERAGE", 20, 0, 0);

        // Two days ago: a seat with drinks, then a tournament entry in the evening.
        Long cart = counterCart(at(-2, 13));
        line(cart, pepsi, 3, 60);
        line(cart, chips, 3, 40);
        saleTwoDaysAgo = sale("GD-0518-001", closedShiftA, at(-2, 13), 500, 300, 0, 0, 0, 800, cart);
        sale("GD-0518-002", closedShiftA, at(-2, 19), 0, 0, 400, 0, 0, 400, null);
        expense(closedShiftA, at(-2, 20), "Napkins and cups", "SUPPLIES", 300);

        // Yesterday: one pre-booking sale.
        bookingSaleYesterday =
                sale("GD-0519-001", closedShiftB, at(-1, 15), 0, 0, 0, 500, 0, 500, null);

        // Today: a bill part-paid in points, an entry plus a booking, and a refund of the booking.
        Long todayCart = counterCart(at(0, 11));
        line(todayCart, pepsi, 2, 60);
        line(todayCart, chips, 2, 40);
        saleToday = sale("GD-0520-001", openShift, at(0, 11), 240, 200, 0, 0, 20, 420, todayCart);
        tournamentAndBookingToday =
                sale("GD-0520-002", openShift, at(0, 18), 0, 0, 200, 300, 0, 500, null);
        refundToday = sale("GD-0520-003", openShift, at(0, 19), 0, 0, 0, -300, 0, -300, null);
        expense(openShift, at(0, 12), "Bulb for PS5-02", "REPAIRS", 200);

        // A counter sale that was voided, and the negative row that reversed it. Both are real
        // postings and both stay; the cart behind them sold nothing in the end.
        Long voidedCart = counterCart(at(0, 20));
        line(voidedCart, water, 5, 20);
        Long voided = sale("GD-0520-004", openShift, at(0, 20), 0, 100, 0, 0, 0, 100, voidedCart);
        jdbc.update("UPDATE transactions SET voided = TRUE, void_reason = 'Wrong bill' WHERE id = ?",
                voided);
        sale("GD-0520-005", openShift, at(0, LocalTime.of(20, 30)), 0, -100, 0, 0, 0, -100,
                voidedCart);
    }

    private Long item(String name, String category, int price, int stock, int reorderAt) {
        return jdbc.queryForObject(
                "INSERT INTO items (name, category, price, stock, reorder_at) "
                        + "VALUES (?, ?, ?, ?, ?) RETURNING id",
                Long.class, name, category, price, stock, reorderAt);
    }

    private Long counterCart(OffsetDateTime createdAt) {
        return jdbc.queryForObject(
                "INSERT INTO carts (settled, created_at) VALUES (TRUE, ?) RETURNING id",
                Long.class, createdAt);
    }

    private void line(Long cartId, Long itemId, int qty, int unitPrice) {
        jdbc.update("INSERT INTO cart_lines (cart_id, item_id, qty, unit_price) VALUES (?, ?, ?, ?)",
                cartId, itemId, qty, unitPrice);
    }

    private Long sale(String publicId, Long shiftId, OffsetDateTime at, int gaming, int fnb,
                      int tournament, int booking, int pointsRedeemed, int totalDue, Long cartId) {
        Long id = jdbc.queryForObject(
                "INSERT INTO transactions (public_id, cart_id, shift_id, staff_id, gaming_amount, "
                        + "fnb_amount, tournament_amount, booking_amount, points_redeemed, "
                        + "total_due, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
                Long.class, publicId, cartId, shiftId, staffId, gaming, fnb, tournament, booking,
                pointsRedeemed, totalDue, at);
        jdbc.update("INSERT INTO payment_splits (tx_id, method, amount) VALUES (?, 'CASH', ?)",
                id, totalDue);
        return id;
    }

    private void expense(Long shiftId, OffsetDateTime at, String description, String category,
                         int amount) {
        jdbc.update("INSERT INTO expenses (shift_id, staff_id, description, category, amount, "
                + "created_at) VALUES (?, ?, ?, ?, ?, ?)", shiftId, staffId, description, category,
                amount, at);
    }

    // ---- seats -------------------------------------------------------------------------------

    /**
     * Three finished visits and one still running. The live one has no {@code ended_at}, so both
     * utilisation and the occupancy tile have to clip it at "now".
     */
    private void floor() {
        closedSession(firstStation, closedShiftA, at(-2, 13), at(-2, 15));
        closedSession(secondStation, closedShiftB, at(-1, 16), at(-1, 17));
        closedSession(firstStation, openShift, at(0, 18), at(0, 19));
        jdbc.update("INSERT INTO sessions (station_id, shift_id, state, consumed_sec, "
                        + "running_since, started_at) VALUES (?, ?, 'RUNNING', 0, ?, ?)",
                secondStation, openShift, at(0, 21), at(0, 21));
    }

    private void closedSession(Long stationId, Long shiftId, OffsetDateTime from,
                               OffsetDateTime to) {
        jdbc.update("INSERT INTO sessions (station_id, shift_id, state, consumed_sec, started_at, "
                + "ended_at) VALUES (?, ?, 'CLOSED', 0, ?, ?)", stationId, shiftId, from, to);
    }

    // ---- bookings and tokens -----------------------------------------------------------------

    /**
     * Every booking state the show-rate cares about: two played, one cancelled and refunded, one
     * paid whose slot has come and gone, one paid that is still to come, and one checked in and
     * waiting for a seat.
     */
    private void bookings() {
        booking(at(-2, 14), "USED", 400, 100, saleTwoDaysAgo, null, at(-2, 10));
        booking(at(-1, 16), "USED", 500, 100, bookingSaleYesterday, null, at(-1, 9));
        booking(at(-1, 18), "CANCELLED", 300, 100, bookingSaleYesterday, refundToday, at(-1, 9));
        booking(at(0, 12), "PAID", 200, 100, saleToday, null, at(0, 8));
        booking(at(0, 23), "PAID", 600, 100, saleToday, null, at(0, 9));
        Long arrived = booking(at(0, 19), "ARRIVED", 300, 100, tournamentAndBookingToday, null,
                at(0, LocalTime.of(9, 30)));

        // Walk-up tokens: one still waiting (the play-ticket half of the pre-sold stat), one
        // already seated, and the checked-in booking's own token, which belongs to neither half.
        token(1, "PLAY_TICKET", null, tournamentAndBookingToday, 240, "WAITING");
        token(2, "PLAY_TICKET", null, tournamentAndBookingToday, 160, "SEATED");
        token(3, "BOOKING", arrived, tournamentAndBookingToday, 300, "WAITING");
    }

    private Long booking(OffsetDateTime startAt, String status, int playAmount, int packageFee,
                         Long txId, Long refundTxId, OffsetDateTime createdAt) {
        return jdbc.queryForObject(
                "INSERT INTO bookings (station_id, name, phone, start_at, blocks, play_amount, "
                        + "package_fee, cutoff_hours, tx_id, status, refund_tx_id, created_at) "
                        + "VALUES (?, ?, '01700000000', ?, 2, ?, ?, 2, ?, ?, ?, ?) RETURNING id",
                Long.class, firstStation, "Guest " + startAt.getHour(), startAt, playAmount,
                packageFee, txId, status, refundTxId, createdAt);
    }

    private void token(int tokenNo, String source, Long bookingId, Long txId, int playAmount,
                       String status) {
        jdbc.update("INSERT INTO queue_entries (token_date, token_no, source, booking_id, tx_id, "
                        + "player_name, console_type, blocks, play_amount, status, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'PS5', 2, ?, ?, ?)",
                TODAY, tokenNo, source, bookingId, txId, "Token " + tokenNo, playAmount, status,
                at(0, 9));
    }
}
