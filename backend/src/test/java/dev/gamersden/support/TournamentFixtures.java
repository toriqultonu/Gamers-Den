package dev.gamersden.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

/**
 * Tournament rows a suite needs to read back or to set up without going through the API — the
 * event a station-guard test has to block a console with, and the raw columns the entry and refund
 * assertions check (B12).
 */
public final class TournamentFixtures {

    private final JdbcTemplate jdbc;

    public TournamentFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** An OPEN event, straight through JDBC — the shortest path to something sellable. */
    public Long openTournament(String name, int entryFee, int maxPlayers, Long createdBy) {
        return jdbc.queryForObject(
                "INSERT INTO tournaments (name, game, cadence, scheduled_at, entry_fee, prize_pool, "
                        + "max_players, match_duration_min, created_by) "
                        + "VALUES (?, 'FIFA 25', 'WEEKLY', now() + interval '1 day', ?, 5000, ?, 20, ?) "
                        + "RETURNING id", Long.class, name, entryFee, maxPlayers, createdBy);
    }

    public void block(Long tournamentId, Long stationId) {
        jdbc.update("INSERT INTO tournament_station_blocks (tournament_id, station_id) VALUES (?, ?)",
                tournamentId, stationId);
    }

    public void setStatus(Long tournamentId, String status) {
        jdbc.update("UPDATE tournaments SET status = ? WHERE id = ?", status, tournamentId);
    }

    public String statusOf(Long tournamentId) {
        return jdbc.queryForObject("SELECT status FROM tournaments WHERE id = ?", String.class,
                tournamentId);
    }

    /** Every entry of an event in seed order, with the columns the money tests care about. */
    public List<Map<String, Object>> entriesOf(Long tournamentId) {
        return jdbc.queryForList("SELECT id, member_id, player_name, tx_id, seed, qr_token, "
                + "checked_in, refunded FROM tournament_entries WHERE tournament_id = ? "
                + "ORDER BY seed", tournamentId);
    }

    /** The whole bracket in drawing order, raw (B13). */
    public List<Map<String, Object>> matchesOf(Long tournamentId) {
        return jdbc.queryForList("SELECT id, round, slot, entry_a, entry_b, winner_entry, "
                + "next_match_id, station_id, started_at, extra_min, decided_by, decided_at "
                + "FROM tournament_matches WHERE tournament_id = ? ORDER BY round, slot",
                tournamentId);
    }

    /** What match start (B14) will stamp — the flag that makes a result execution, not a ruling. */
    public void startMatch(Long matchId) {
        jdbc.update("UPDATE tournament_matches SET started_at = now() WHERE id = ?", matchId);
    }

    public Long winnerEntryOf(Long tournamentId) {
        return jdbc.queryForObject("SELECT winner_entry_id FROM tournaments WHERE id = ?",
                Long.class, tournamentId);
    }

    public List<Map<String, Object>> refundsOf(Long shiftId) {
        return jdbc.queryForList("SELECT id, public_id, total_due, tournament_amount, member_id "
                + "FROM transactions WHERE shift_id = ? AND total_due < 0 ORDER BY id", shiftId);
    }

    public List<Map<String, Object>> tendersOf(Long txId) {
        return jdbc.queryForList(
                "SELECT method, amount, payment_ref FROM payment_splits WHERE tx_id = ? ORDER BY id",
                txId);
    }
}
