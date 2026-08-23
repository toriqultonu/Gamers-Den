package dev.gamersden.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

/**
 * Booking and queue rows a suite needs to read back or to set up without going through the API —
 * the settings a disabled-feature test flips, and the raw columns the money, token, seat and
 * refund assertions check (B15, B16).
 */
public final class BookingFixtures {

    private final JdbcTemplate jdbc;

    public BookingFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- settings -----------------------------------------------------------------------------

    /** Straight through JDBC, so a test can change the settings without an Admin token. */
    public void settings(boolean enabled, int packageFee, int cancelCutoffHours) {
        jdbc.update("UPDATE booking_settings SET enabled = ?, package_fee = ?, "
                + "cancel_cutoff_hours = ?", enabled, packageFee, cancelCutoffHours);
    }

    public void enabled(boolean enabled) {
        jdbc.update("UPDATE booking_settings SET enabled = ?", enabled);
    }

    public Map<String, Object> settings() {
        return jdbc.queryForMap("SELECT enabled, package_fee, cancel_cutoff_hours, updated_by "
                + "FROM booking_settings");
    }

    // ---- bookings -----------------------------------------------------------------------------

    /** One booking with the columns the lifecycle assertions care about. */
    public Map<String, Object> booking(long bookingId) {
        return jdbc.queryForMap("SELECT id, station_id, member_id, name, phone, start_at, blocks, "
                + "play_amount, package_fee, cutoff_hours, tx_id, status, refund_tx_id, "
                + "queue_entry_id FROM bookings WHERE id = ?", bookingId);
    }

    public String statusOf(long bookingId) {
        return jdbc.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class,
                bookingId);
    }

    // ---- tokens -------------------------------------------------------------------------------

    /** Every token issued today, in the order the counter handed them out. */
    public List<Map<String, Object>> tokensToday() {
        return jdbc.queryForList("SELECT id, token_date, token_no, source, booking_id, tx_id, "
                + "player_name, console_type, blocks, play_amount, status, session_id "
                + "FROM queue_entries ORDER BY token_no");
    }

    public Map<String, Object> token(long queueEntryId) {
        return jdbc.queryForMap("SELECT id, token_date, token_no, source, booking_id, tx_id, "
                + "player_name, console_type, blocks, play_amount, status, session_id "
                + "FROM queue_entries WHERE id = ?", queueEntryId);
    }

    public String tokenStatusOf(long queueEntryId) {
        return jdbc.queryForObject("SELECT status FROM queue_entries WHERE id = ?", String.class,
                queueEntryId);
    }

    /** What the daily counter will hand out next for {@code day} — absent until it has counted. */
    public Integer nextTokenNoOn(java.time.LocalDate day) {
        return jdbc.query("SELECT next_no FROM token_seq WHERE token_date = ?",
                rs -> rs.next() ? rs.getInt(1) : null, day);
    }

    /**
     * The prepaid blocks a seat inserted: what each cost and which sale is already carrying it.
     * A block born paid is indistinguishable from one settled mid-session, which is the point —
     * {@code paid_tx_id} is a plain column and the end guard only asks whether it is set (§5.9).
     */
    public List<Map<String, Object>> blocksOf(long sessionId) {
        return jdbc.queryForList("SELECT price, paid_tx_id FROM session_blocks "
                + "WHERE session_id = ? AND NOT removed ORDER BY id", sessionId);
    }

    // ---- money --------------------------------------------------------------------------------

    public Map<String, Object> transaction(long txId) {
        return jdbc.queryForMap("SELECT id, public_id, session_id, cart_id, member_id, shift_id, "
                + "gaming_amount, fnb_amount, tournament_amount, booking_amount, points_redeemed, "
                + "points_earned, total_due FROM transactions WHERE id = ?", txId);
    }

    public List<Map<String, Object>> tendersOf(long txId) {
        return jdbc.queryForList(
                "SELECT method, amount, payment_ref FROM payment_splits WHERE tx_id = ? ORDER BY id",
                txId);
    }

    /** The negative rows a shift is holding — a cancel's refund is one of these. */
    public List<Map<String, Object>> refundsOf(Long shiftId) {
        return jdbc.queryForList("SELECT id, public_id, total_due, booking_amount, member_id "
                + "FROM transactions WHERE shift_id = ? AND total_due < 0 ORDER BY id", shiftId);
    }

    public String paperOf(long printJobId) {
        return jdbc.queryForObject("SELECT rendered_text FROM print_jobs WHERE id = ?",
                String.class, printJobId);
    }

    public String printJobTypeOf(long printJobId) {
        return jdbc.queryForObject("SELECT type FROM print_jobs WHERE id = ?", String.class,
                printJobId);
    }
}
