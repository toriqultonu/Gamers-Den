package dev.gamersden.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * Floor rows the B05 suite needs but B05 has no API for yet — sessions, their blocks and the
 * shift they belong to arrive with B06 and B11. Written straight through JDBC so the station and
 * pricing tests can exercise the "live session" guards without pre-empting those tasks.
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
                "SELECT price FROM session_blocks WHERE session_id = ? ORDER BY id", Integer.class, sessionId);
    }
}
