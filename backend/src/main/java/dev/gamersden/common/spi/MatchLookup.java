package dev.gamersden.common.spi;

import java.util.Map;
import java.util.Optional;

/**
 * The narrow read the {@code station} package needs from {@code tournament} — the match being
 * played on a console right now, so the Floor can count it down like a session
 * (docs/tournaments.md §4, "Floor integration").
 *
 * <p>A reserved console with a started match shows the match countdown; a reserved console
 * without one keeps reading "Reserved · tournament". Everything here is derived on the read from
 * {@code started_at}, {@code extra_min} and the event's match duration — the countdown is never
 * stored (invariants §5.1, §5.4).
 *
 * <p>Implemented by {@code tournament/domain/MatchExecutionService}.
 */
public interface MatchLookup {

    /** Every console currently hosting an undecided started match, keyed by station id. */
    Map<Long, LiveMatch> liveMatchesByStation();

    /** The match on one console, empty when none is in play there. */
    Optional<LiveMatch> liveMatchOn(long stationId);

    /**
     * A station card's match line.
     *
     * @param remainingSeconds {@code (matchDurationMin + extraMin) · 60 − elapsed}, floored at 0
     * @param timeUp           true once that hits zero — the card's "match over" state; the seat
     *                         is not free again until somebody records the winner
     */
    record LiveMatch(long tournamentId,
                     long matchId,
                     String tournamentName,
                     int round,
                     int slot,
                     String playerA,
                     String playerB,
                     long remainingSeconds,
                     boolean timeUp) {
    }
}
