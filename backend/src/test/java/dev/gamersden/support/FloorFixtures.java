package dev.gamersden.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * Floor rows a suite needs but the task under test has no API for — the shift a session belongs
 * to (B11), the paid-block and cart state a settle would leave behind (B07/B10), and the seeded
 * sessions the station guards read. Written straight through JDBC so the session, station and
 * pricing tests can exercise those guards without pre-empting the tasks that own them.
 */
public final class FloorFixtures {

    private final JdbcTemplate jdbc;

    public FloorFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Long openShift(Long staffId, String terminal) {
        return jdbc.queryForObject(
                "INSERT INTO shifts (staff_id, terminal, opening_float) VALUES (?, ?, 2000) "
                        + "RETURNING id", Long.class, staffId, terminal);
    }

    /** A running session with {@code blocks} paid-for blocks, each snapshotted at {@code price}. */
    public Long runningSessionOn(Long stationId, Long shiftId, int blocks, int price, int consumedSec) {
        Long sessionId = jdbc.queryForObject(
                "INSERT INTO sessions (station_id, shift_id, state, consumed_sec, running_since) "
                        + "VALUES (?, ?, 'RUNNING', ?, now()) RETURNING id",
                Long.class, stationId, shiftId, consumedSec);
        for (int i = 0; i < blocks; i++) {
            jdbc.update("INSERT INTO session_blocks (session_id, price) VALUES (?, ?)", sessionId, price);
        }
        return sessionId;
    }

    public Long closedSessionOn(Long stationId, Long shiftId) {
        return jdbc.queryForObject(
                "INSERT INTO sessions (station_id, shift_id, state, ended_at) "
                        + "VALUES (?, ?, 'CLOSED', now()) RETURNING id",
                Long.class, stationId, shiftId);
    }

    public List<Integer> blockPricesOf(Long sessionId) {
        return jdbc.queryForList(
                "SELECT price FROM session_blocks WHERE session_id = ? AND NOT removed ORDER BY id",
                Integer.class, sessionId);
    }

    public String stateOf(Long sessionId) {
        return jdbc.queryForObject("SELECT state FROM sessions WHERE id = ?", String.class, sessionId);
    }

    public int consumedSecOf(Long sessionId) {
        return jdbc.queryForObject("SELECT consumed_sec FROM sessions WHERE id = ?",
                Integer.class, sessionId);
    }

    public int removedBlockCountOf(Long sessionId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM session_blocks WHERE session_id = ? AND removed",
                Integer.class, sessionId);
    }

    /**
     * What a settle leaves behind (B10): every live block carrying the transaction that paid for
     * it. The same shape a prepaid block is born with — {@code paid_tx_id} is a plain column, so a
     * seat from a booking or a play ticket is indistinguishable here by design (invariant §5.9).
     */
    public void markBlocksPaid(Long sessionId, long txId) {
        jdbc.update("UPDATE session_blocks SET paid_tx_id = ? WHERE session_id = ? AND NOT removed",
                txId, sessionId);
    }

    /** Prepaid blocks born paid, exactly as B16's seat transaction will insert them. */
    public void prepaidBlocksOn(Long sessionId, int blocks, int price, long saleTxId) {
        for (int i = 0; i < blocks; i++) {
            jdbc.update("INSERT INTO session_blocks (session_id, price, paid_tx_id) VALUES (?, ?, ?)",
                    sessionId, price, saleTxId);
        }
    }

    /** An unsettled cart on the session — the F&amp;B half of the net-outstanding end guard. */
    public Long unsettledCartOn(Long sessionId, String itemName, int unitPrice, int qty) {
        Long itemId = jdbc.queryForObject(
                "INSERT INTO items (name, category, price, stock) VALUES (?, 'BEVERAGE', ?, 100) "
                        + "RETURNING id", Long.class, itemName, unitPrice);
        Long cartId = jdbc.queryForObject(
                "INSERT INTO carts (session_id) VALUES (?) RETURNING id", Long.class, sessionId);
        jdbc.update("INSERT INTO cart_lines (cart_id, item_id, qty, unit_price) VALUES (?, ?, ?, ?)",
                cartId, itemId, qty, unitPrice);
        return cartId;
    }

    /** What {@code POST /payments} does to the cart it just charged for (B10). */
    public void settleCart(Long cartId) {
        jdbc.update("UPDATE carts SET settled = TRUE WHERE id = ?", cartId);
    }
}
